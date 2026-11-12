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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
