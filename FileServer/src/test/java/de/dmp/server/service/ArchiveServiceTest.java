package de.dmp.server.service;

import de.dmp.server.data.dto.MetadataDto;
import de.dmp.server.service.archive.ArchiveService;
import de.dmp.server.service.archive.ArchivedFile;
import de.dmp.server.service.archive.ArchiveContext;
import de.dmp.server.service.archive.ops.ArchiveOperation;
import de.dmp.server.service.archive.ops.DeleteOperation;
import de.dmp.server.service.archive.ops.RetrieveOperation;
import de.dmp.server.service.archive.ops.StoreOperation;
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
	void storesAndRetrievesArchives() throws Exception {

		byte[] contents = "archive contents".getBytes();
		String firstKey = archive.store(filePart(contents)).block(Duration.ofSeconds(5));
		String secondKey = archive.store(filePart(contents)).block(Duration.ofSeconds(5));
		assertNotEquals(firstKey, secondKey);

		for (String key : Set.of(firstKey, secondKey)) {
			MetadataDto metadata = new MetadataDto("file.txt", "description", key, Set.of());
			ArchivedFile result = archive.retrieve(metadata).block(Duration.ofSeconds(5));
			assertNotNull(result);
			assertEquals(contents.length, result.size());
			try (var input = result.content()
			                       .getInputStream()) {
				assertArrayEquals(contents, input.readAllBytes());
			}
			archive.delete(key).block(Duration.ofSeconds(5));
			assertNull(archive.retrieve(metadata).block(Duration.ofSeconds(5)));
		}
		try (var files = Files.list(uploadDir)) {
			assertEquals(0, files.count());
		}
	}

	@Test
	void transferFailuresAreReportedAndTemporaryFilesRemoved() throws Exception {

		FilePart file = mock(FilePart.class);
		when(file.transferTo(any(Path.class))).thenReturn(Mono.error(new IllegalStateException("transfer failed")));
		assertThrows(IllegalStateException.class,
				() -> archive.store(file).block(Duration.ofSeconds(5)));
		try (var files = Files.list(uploadDir)) {
			assertEquals(0, files.count());
		}
	}

	@Test
	void reactiveStoreTransfersOnceAndRetrievesArchive() throws Exception {

		byte[] contents = "reactive archive contents".getBytes();
		FilePart file = filePart(contents);
		Mono<String> result = archive.store(file);
		verify(file, times(0)).transferTo(any(Path.class));

		String key = result.subscribeOn(Schedulers.parallel()).block(Duration.ofSeconds(5));
		assertEquals(key, result.block(Duration.ofSeconds(5)));
		verify(file, times(1)).transferTo(any(Path.class));
		MetadataDto metadata = new MetadataDto("file.txt", "description", key, Set.of());
		try (var input = archive.retrieve(metadata).block(Duration.ofSeconds(5)).content().getInputStream()) {
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
				() -> archive.store(file).block(Duration.ofSeconds(5)));
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
		Mono<String> result = archive.store(file);
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
		try (var input = archive.retrieve(metadata).block(Duration.ofSeconds(5)).content().getInputStream()) {
			assertArrayEquals(contents, input.readAllBytes());
		}
		verify(file, times(1)).transferTo(any(Path.class));
	}

	@Test
	void corruptArchiveErrorsPropagateFromRetrieve() throws Exception {

		Files.writeString(uploadDir.resolve("bad.zip"), "not a zip");
		MetadataDto metadata = new MetadataDto("file.txt", "description", "bad", Set.of());
		RuntimeException failure = assertThrows(RuntimeException.class,
				() -> archive.retrieve(metadata).block(Duration.ofSeconds(5)));
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
		ArchivedFile result = archive.retrieve(metadata).block(Duration.ofSeconds(5));
		assertNotNull(result);
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
		assertNull(archive.retrieve(missing).block(Duration.ofSeconds(5)));
		try (var zip = new ZipOutputStream(Files.newOutputStream(uploadDir.resolve("empty.zip")))) {
			zip.finish();
		}
		MetadataDto empty = new MetadataDto("file.txt", "description", "empty", Set.of());
		assertNull(archive.retrieve(empty).block(Duration.ofSeconds(5)));
	}

	@Test
	void openingRetrievedContentReportsArchiveRemoval() throws Exception {

		String key = archive.store(filePart("content".getBytes())).block(Duration.ofSeconds(5));
		MetadataDto metadata = new MetadataDto("file.txt", "description", key, Set.of());
		ArchivedFile result = archive.retrieve(metadata).block(Duration.ofSeconds(5));
		assertNotNull(result);
		archive.delete(key).block(Duration.ofSeconds(5));
		assertThrows(IOException.class, () -> result.content()
		                                            .getInputStream());
	}

	@Test
	void rejectsPathsOutsideArchiveDirectory() {

		RuntimeException failure = assertThrows(RuntimeException.class,
				() -> archive.delete("../outside").block(Duration.ofSeconds(5)));
		assertInstanceOf(IOException.class, failure.getCause());
	}

	@Test
	void deletesOnlyOnSubscription() throws Exception {
		Path path = uploadDir.resolve("lazy.zip");
		Files.createFile(path);
		Mono<Void> deletion = archive.delete("lazy");
		assertTrue(Files.exists(path));
		deletion.block(Duration.ofSeconds(5));
		assertFalse(Files.exists(path));
	}

	@Test
	void rejectsInvalidWorkerCount() {
		assertThrows(IllegalArgumentException.class, () -> new ArchiveService(uploadDir, 0));
	}

	@Test
	void operationsShareTheReactiveContract() throws Exception {
		var context = ArchiveContext.create(uploadDir);
		var scheduler = Schedulers.newBoundedElastic(1, 10, "archive-test");
		try {
			ArchiveOperation<String> store = new StoreOperation(filePart("content".getBytes()));
			String key = store.execute(context, scheduler).block(Duration.ofSeconds(5));
			assertNotNull(key);
			ArchiveOperation<ArchivedFile> retrieve = new RetrieveOperation(
					new MetadataDto("file.txt", "description", key, Set.of()));
			ArchivedFile result = retrieve.execute(context, scheduler).block(Duration.ofSeconds(5));
			assertNotNull(result);
			try (var input = result.content().getInputStream()) {
				assertArrayEquals("content".getBytes(), input.readAllBytes());
			}
			ArchiveOperation<Void> delete = new DeleteOperation(key);
			delete.execute(context, scheduler).block(Duration.ofSeconds(5));
			assertNull(retrieve.execute(context, scheduler).block(Duration.ofSeconds(5)));
		} finally {
			scheduler.dispose();
		}
	}

}
