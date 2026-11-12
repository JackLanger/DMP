package de.dmp.server.service;

import de.dmp.server.data.dto.MetadataDto;
import de.dmp.server.service.archive.ArchiveService;
import de.dmp.server.service.archive.ArchivedFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArchiveServiceTest {

	@TempDir
	Path uploadDir;
	private ArchiveService archive;

	@BeforeEach
	void setUp() throws IOException {

		archive = new ArchiveService(uploadDir);
	}

	@AfterEach
	void tearDown() {

		archive.close();
	}

	private FilePart filePart(byte[] contents) {

		FilePart file = mock(FilePart.class);
		when(file.transferTo(any(Path.class))).thenAnswer(invocation -> Mono.fromRunnable(() -> {
			try {
				Files.write(invocation.getArgument(0), contents);
			} catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}));
		return file;
	}

	@Test
	void storesSynchronouslyAndAsynchronouslyAndRetrievesArchives() throws Exception {

		byte[] contents = "archive contents".getBytes();
		String syncKey = archive.store(filePart(contents));
		String asyncKey = archive.storeAsync(filePart(contents))
		                         .get(5, TimeUnit.SECONDS);
		assertNotEquals(syncKey, asyncKey);

		for (String key : Set.of(syncKey, asyncKey)) {
			MetadataDto metadata = new MetadataDto("file.txt", "description", key, Set.of());
			ArchivedFile result = archive.retrieve(metadata)
			                             .orElseThrow();
			assertEquals(contents.length, result.size());
			try (var input = result.content()
			                       .getInputStream()) {
				assertArrayEquals(contents, input.readAllBytes());
			}
			archive.delete(key);
			assertTrue(archive.retrieve(metadata)
			                  .isEmpty());
		}
		try (var files = Files.list(uploadDir)) {
			assertEquals(0, files.count());
		}
	}

	@Test
	void asynchronousFailuresAreReportedAndTemporaryFilesRemoved() throws Exception {

		FilePart file = mock(FilePart.class);
		when(file.transferTo(any(Path.class))).thenReturn(Mono.error(new IllegalStateException("transfer failed")));
		ExecutionException failure = assertThrows(ExecutionException.class,
		                                          () -> archive.storeAsync(file)
		                                                       .get(5, TimeUnit.SECONDS));
		assertInstanceOf(IllegalStateException.class, failure.getCause());
		try (var files = Files.list(uploadDir)) {
			assertEquals(0, files.count());
		}
	}

	@Test
	void reactiveStoreTransfersOnceAndRetrievesArchive() throws Exception {

		byte[] contents = "reactive archive contents".getBytes();
		FilePart file = filePart(contents);
		Mono<String> result = archive.storeReactive(file);
		verify(file, times(0)).transferTo(any(Path.class));

		String key = result.subscribeOn(Schedulers.parallel()).block(Duration.ofSeconds(5));
		assertEquals(key, result.block(Duration.ofSeconds(5)));
		verify(file, times(1)).transferTo(any(Path.class));
		MetadataDto metadata = new MetadataDto("file.txt", "description", key, Set.of());
		try (var input = archive.retrieve(metadata).orElseThrow().content().getInputStream()) {
			assertArrayEquals(contents, input.readAllBytes());
		}
		try (var files = Files.list(uploadDir)) {
			assertEquals(Set.of(key + ".zip"), files.map(path -> path.getFileName().toString())
			                                         .collect(java.util.stream.Collectors.toSet()));
		}
	}

	@Test
	void reactiveTransferFailureCleansTemporaryFile() throws Exception {

		FilePart file = mock(FilePart.class);
		var failure = new IllegalStateException("transfer failed");
		when(file.transferTo(any(Path.class))).thenAnswer(invocation -> Mono.fromRunnable(() -> {
			try {
				Files.writeString(invocation.getArgument(0), "partial upload");
			} catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}).then(Mono.error(failure)));
		IllegalStateException actual = assertThrows(IllegalStateException.class,
				() -> archive.storeReactive(file).block(Duration.ofSeconds(5)));
		assertSame(failure, actual);
		try (var files = Files.list(uploadDir)) {
			assertEquals(0, files.count());
		}
	}

	@Test
	void cancellingSubscriberDoesNotInterruptTransferOrCleanFileEarly() throws Exception {

		FilePart file = mock(FilePart.class);
		var destination = new CompletableFuture<Path>();
		Sinks.Empty<Void> transfer = Sinks.empty();
		when(file.transferTo(any(Path.class))).thenAnswer(invocation -> Mono.defer(() -> {
			destination.complete(invocation.getArgument(0));
			return transfer.asMono();
		}));
		Mono<String> result = archive.storeReactive(file);
		var subscription = result.subscribe();
		Path temporary = destination.get(5, TimeUnit.SECONDS);
		subscription.dispose();
		assertTrue(Files.exists(temporary));
		byte[] contents = "completed after cancellation".getBytes();
		Files.write(temporary, contents);
		assertEquals(Sinks.EmitResult.OK, transfer.tryEmitEmpty());

		String key = result.block(Duration.ofSeconds(5));
		assertFalse(Files.exists(temporary));
		MetadataDto metadata = new MetadataDto("file.txt", "description", key, Set.of());
		try (var input = archive.retrieve(metadata).orElseThrow().content().getInputStream()) {
			assertArrayEquals(contents, input.readAllBytes());
		}
		verify(file, times(1)).transferTo(any(Path.class));
	}

	@Test
	void corruptArchiveErrorsPropagateFromRetrieveAsync() throws Exception {

		Files.writeString(uploadDir.resolve("bad.zip"), "not a zip");
		MetadataDto metadata = new MetadataDto("file.txt", "description", "bad", Set.of());
		ExecutionException failure = assertThrows(ExecutionException.class,
		                                          () -> archive.retrieveAsync(metadata)
		                                                       .get(5, TimeUnit.SECONDS));
		assertInstanceOf(IOException.class, failure.getCause());
	}

	@Test
	void retrievesFirstEntryLazilyAndClosesItsStream() throws Exception {

		Path path = uploadDir.resolve("multiple.zip");
		try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
			zip.putNextEntry(new ZipEntry("first.txt"));
			zip.write("first".getBytes());
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("second.txt"));
			zip.write("second".getBytes());
			zip.closeEntry();
		}
		MetadataDto metadata = new MetadataDto("file.txt", "description", "multiple", Set.of());
		ArchivedFile result = archive.retrieveAsync(metadata)
		                             .get(5, TimeUnit.SECONDS)
		                             .orElseThrow();
		assertEquals(5, result.size());
		var input = result.content()
		                  .getInputStream();
		assertArrayEquals("first".getBytes(), input.readAllBytes());
		input.close();
		assertThrows(IOException.class, input::read);
	}

	@Test
	void missingAndEmptyArchivesReturnEmptyResults() throws Exception {

		MetadataDto missing = new MetadataDto("file.txt", "description", "missing", Set.of());
		assertTrue(archive.retrieveAsync(missing)
		                  .get(5, TimeUnit.SECONDS)
		                  .isEmpty());
		try (var zip = new ZipOutputStream(Files.newOutputStream(uploadDir.resolve("empty.zip")))) {
			zip.finish();
		}
		MetadataDto empty = new MetadataDto("file.txt", "description", "empty", Set.of());
		assertTrue(archive.retrieve(empty)
		                  .isEmpty());
	}

	@Test
	void openingRetrievedContentReportsArchiveRemoval() throws Exception {

		String key = archive.store(filePart("content".getBytes()));
		MetadataDto metadata = new MetadataDto("file.txt", "description", key, Set.of());
		ArchivedFile result = archive.retrieve(metadata)
		                             .orElseThrow();
		archive.delete(key);
		assertThrows(IOException.class, () -> result.content()
		                                            .getInputStream());
	}

	@Test
	void rejectsPathsOutsideArchiveDirectory() {

		assertThrows(IOException.class, () -> archive.delete("../outside"));
	}

}
