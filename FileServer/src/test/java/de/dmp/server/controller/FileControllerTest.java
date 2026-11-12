package de.dmp.server.controller;


import de.dmp.server.data.dto.MetadataDto;
import de.dmp.server.data.Metadata;
import de.dmp.server.service.ReactiveMetadataService;
import de.dmp.server.service.archive.ArchiveService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class FileControllerTest {

	@TempDir
	Path uploadDir;

	private ReactiveMetadataService uploadMetadataService;

	private FileController controller;
	private ArchiveService archive;


	private static MetadataDto metadata(UUID id, String name, String fileLocation) {

		return new MetadataDto(id, name, "description", fileLocation, Instant.now(), Set.of());
	}


	private static FilePart filePart(String name, byte[] contents) {

		FilePart file = mock(FilePart.class);
		when(file.filename()).thenReturn(name);
		when(file.transferTo(any(Path.class))).thenAnswer(invocation -> Mono.fromRunnable(() -> {
			try {
				Files.write(invocation.getArgument(0), contents);
			} catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}));
		return file;
	}


	private static void writeArchive(Path path, byte[] contents) throws IOException {

		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
			zip.putNextEntry(new ZipEntry("report.txt"));
			zip.write(contents);
			zip.closeEntry();
		}
	}


	@SuppressWarnings("all")
	private static void writeArchive(Path path) throws IOException {

		try (ZipOutputStream ignored = new ZipOutputStream(Files.newOutputStream(path))) {
			// no touchy
		}
	}


	private static void assertArchiveContains(Path archive, byte[] expected) throws IOException {

		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
			assertNotNull(zip.getNextEntry());
			assertArrayEquals(expected, zip.readAllBytes());
		}
	}


	@BeforeEach
	void setUp() throws IOException {

		uploadMetadataService = mock(ReactiveMetadataService.class);
		when(uploadMetadataService.create(any())).thenAnswer(invocation -> Mono.just(new Metadata()));
		when(uploadMetadataService.update(any(), any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
		archive = new ArchiveService(uploadDir);
		controller = new FileController(archive, uploadMetadataService);
	}

	@AfterEach
	void tearDown() {

		archive.close();
	}

	@Nested
	class ListFiles {

		@Test
		void returnsAllMetadata() {

			List<MetadataDto> expected = List.of(metadata(UUID.randomUUID(), "report.pdf", "storage-key"));
			when(uploadMetadataService.findAll()).thenReturn(Flux.fromIterable(expected));

			assertEquals(expected, controller.listFiles().block(Duration.ofSeconds(5)));
			verify(uploadMetadataService).findAll();
		}

	}


	@Nested
	class ListFilesByTag {

		@Test
		void filtersMetadataByTagName() {

			List<MetadataDto> expected = List.of(metadata(UUID.randomUUID(), "report.pdf", "storage-key"));
			when(uploadMetadataService.findByTagName("research")).thenReturn(Flux.fromIterable(expected));

			assertEquals(expected, controller.listFilesByTag("research").block(Duration.ofSeconds(5)));
			verify(uploadMetadataService).findByTagName("research");
		}

	}


	@Nested
	class GetFileById {

		@Test
		void returnsMetadataForId() {

			UUID id = UUID.randomUUID();
			MetadataDto expected = metadata(id, "report.pdf", "storage-key");
			when(uploadMetadataService.findDtoById(id)).thenReturn(Mono.just(expected));

			assertEquals(Optional.of(expected), controller.getFileById(id).block(Duration.ofSeconds(5)));
			verify(uploadMetadataService).findDtoById(id);
		}


		@Test
		void returnsEmptyWhenIdDoesNotExist() {

			UUID id = UUID.randomUUID();
			when(uploadMetadataService.findDtoById(id)).thenReturn(Mono.empty());

			assertEquals(Optional.empty(), controller.getFileById(id).block(Duration.ofSeconds(5)));
			verify(uploadMetadataService).findDtoById(id);
		}

	}


	@Nested
	class Download {

		@Test
		void streamsDownloadThroughWebFlux() throws IOException {
			UUID id = UUID.randomUUID();
			byte[] contents = new byte[32768];
			new java.util.Random(1).nextBytes(contents);
			when(uploadMetadataService.findDtoById(id))
					.thenReturn(Mono.just(metadata(id, "report.txt", "webflux-key")));
			writeArchive(uploadDir.resolve("webflux-key.zip"), contents);

			WebTestClient.bindToController(controller).build()
					.get().uri("/files/{id}/download", id).exchange()
					.expectStatus().isOk()
					.expectHeader().contentType(MediaType.APPLICATION_OCTET_STREAM)
					.expectHeader().contentLength(contents.length)
					.expectBody(byte[].class).isEqualTo(contents);
		}

		@Test
		void readsDownloadContentOffTheCallingThread() {
			UUID id = UUID.randomUUID();
			when(uploadMetadataService.findDtoById(id))
					.thenReturn(Mono.just(metadata(id, "report.txt", "key")));
			var readThread = new java.util.concurrent.atomic.AtomicReference<String>();
			var content = new ByteArrayResource("content".getBytes()) {
				@Override
				public java.io.InputStream getInputStream() throws IOException {
					readThread.set(Thread.currentThread().getName());
					return super.getInputStream();
				}
			};
			var mockedArchive = mock(de.dmp.server.service.archive.Archive.class);
			when(mockedArchive.retrieve(any())).thenReturn(Mono.just(
					new de.dmp.server.service.archive.ArchivedFile(content, content.contentLength())));
			var reactiveController = new FileController(mockedArchive, uploadMetadataService);
			DataBuffer data = reactiveController.download(id).flatMap(response -> DataBufferUtils.join(response.getBody()))
					.subscribeOn(reactor.core.scheduler.Schedulers.parallel()).block(Duration.ofSeconds(5));
			assertNotNull(data);
			DataBufferUtils.release(data);
			assertTrue(readThread.get().startsWith("boundedElastic-"), readThread.get());
		}

		@Test
		void streamsFirstEntryOfArchiveWithDownloadHeaders() throws IOException {

			UUID id = UUID.randomUUID();
			byte[] contents = "file contents".getBytes();
			when(uploadMetadataService.findDtoById(id)).thenReturn(Mono.just(metadata(id, "report.txt", "archive-key")));
			writeArchive(uploadDir.resolve("archive-key.zip"), contents);

			ResponseEntity<Flux<DataBuffer>> response = controller.download(id)
			                                              .block(Duration.ofSeconds(5));

			assertNotNull(response);
			assertEquals(HttpStatus.OK, response.getStatusCode());
			assertEquals(
					MediaType.APPLICATION_OCTET_STREAM,
					response.getHeaders()
					        .getContentType()
			            );
			assertEquals(
					contents.length,
					response.getHeaders()
					        .getContentLength()
			            );
			assertEquals(
					"report.txt",
					response.getHeaders()
					        .getContentDisposition()
					        .getFilename()
			            );
			assert response.getBody() != null;
			DataBuffer data = DataBufferUtils.join(response.getBody()).block(Duration.ofSeconds(5));
			assertNotNull(data);
			byte[] actual = new byte[data.readableByteCount()];
			data.read(actual);
			DataBufferUtils.release(data);
			assertArrayEquals(contents, actual);
			verify(uploadMetadataService).findDtoById(id);
		}


		@Test
		void returnsNotFoundWhenMetadataDoesNotExist() {

			UUID id = UUID.randomUUID();
			when(uploadMetadataService.findDtoById(id)).thenReturn(Mono.empty());

			ResponseEntity<Flux<DataBuffer>> response = controller.download(id)
			                                              .block(Duration.ofSeconds(5));

			assertNotNull(response);
			assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
			verify(uploadMetadataService).findDtoById(id);
		}


		@Test
		void returnsNotFoundWhenArchiveIsMissing() {

			UUID id = UUID.randomUUID();
			when(uploadMetadataService.findDtoById(id)).thenReturn(Mono.just(metadata(id, "report.txt", "missing-archive")));

			ResponseEntity<Flux<DataBuffer>> response = controller.download(id)
			                                              .block(Duration.ofSeconds(5));

			assertNotNull(response);
			assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
		}


		@Test
		void returnsNotFoundWhenArchiveIsEmpty() throws IOException {

			UUID id = UUID.randomUUID();
			when(uploadMetadataService.findDtoById(id)).thenReturn(Mono.just(metadata(id, "report.txt", "empty-archive")));
			writeArchive(uploadDir.resolve("empty-archive.zip"));

			ResponseEntity<Flux<DataBuffer>> response = controller.download(id)
			                                              .block(Duration.ofSeconds(5));

			assertNotNull(response);
			assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
		}

		@Test
		void propagatesCorruptArchiveErrors() throws IOException {

			UUID id = UUID.randomUUID();
			when(uploadMetadataService.findDtoById(id)).thenReturn(Mono.just(metadata(id, "report.txt", "bad-archive")));
			Files.writeString(uploadDir.resolve("bad-archive.zip"), "not a zip");

			Throwable failure = assertThrows(RuntimeException.class,
			                                 () -> controller.download(id)
			                                                 .block(Duration.ofSeconds(5)));
			assertInstanceOf(IOException.class, failure.getCause());
		}

	}


	@Nested
	class GetFilesByName {

		@Test
		void returnsMetadataMatchingNameQuery() {

			List<MetadataDto> expected = List.of(metadata(UUID.randomUUID(), "report.pdf", "storage-key"));
			when(uploadMetadataService.findByName("report.*")).thenReturn(Flux.fromIterable(expected));

			assertEquals(expected, controller.getFilesByName("report.*").block(Duration.ofSeconds(5)));
			verify(uploadMetadataService).findByName("report.*");
		}

	}


	@Nested
	class Upload {

		@Test
		void acceptsMultipartThroughWebFlux() throws IOException {
			byte[] contents = "multipart upload".getBytes();
			MultipartBodyBuilder body = new MultipartBodyBuilder();
			body.part("file", new ByteArrayResource(contents) {
				@Override public String getFilename() {
					return "report.txt";
				}
			});
			body.part("metadata", "{\"description\":\"description\",\"tags\":[\"research\"]}")
			    .contentType(MediaType.APPLICATION_JSON);

			String key = WebTestClient.bindToController(controller).build()
					.post().uri("/files")
					.contentType(MediaType.MULTIPART_FORM_DATA)
					.body(BodyInserters.fromMultipartData(body.build()))
					.exchange()
					.expectStatus().isCreated()
					.expectBody(String.class).returnResult().getResponseBody();

			assertNotNull(key);
			assertArchiveContains(uploadDir.resolve(key + ".zip"), contents);
			var created = org.mockito.ArgumentCaptor.forClass(MetadataDto.class);
			verify(uploadMetadataService).create(created.capture());
			assertEquals("report.txt", created.getValue().name());
			assertEquals(Set.of("research"), created.getValue().tags());
		}

		@Test
		void createsMetadataAndArchiveForNewUpload() throws IOException {

			byte[] contents = "upload contents".getBytes();
			FilePart file = filePart("report.txt", contents);
			UploadMetadataRequest request = new UploadMetadataRequest(null, "description", Set.of("research"));

			ResponseEntity<String> response = controller.upload(file, request)
			                                            .block(Duration.ofSeconds(5));

			assertNotNull(response);
			assertEquals(HttpStatus.CREATED, response.getStatusCode());
			assertNotNull(response.getBody());
			var created = org.mockito.ArgumentCaptor.forClass(MetadataDto.class);
			verify(uploadMetadataService).create(created.capture());
			assertEquals(
					"report.txt",
					created.getValue()
					       .name()
			            );
			assertEquals(
					"description",
					created.getValue()
					       .description()
			            );
			assertEquals(
					Set.of("research"),
					created.getValue()
					       .tags()
			            );
			assertArchiveContains(uploadDir.resolve(response.getBody() + ".zip"), contents);
			try (var files = Files.list(uploadDir)) {
				assertEquals(1, files.count());
			}
			verify(uploadMetadataService, never()).update(any(), any());
		}


		@Test
		void updatesMetadataAndRemovesReplacedArchive() throws IOException {

			UUID id = UUID.randomUUID();
			byte[] contents = "replacement contents".getBytes();
			FilePart file = filePart("replacement.txt", contents);
			Metadata oldMetadata = new Metadata();
			oldMetadata.setId(id);
			oldMetadata.setFileLocation("old-storage-key");
			when(uploadMetadataService.findById(id)).thenReturn(Mono.just(oldMetadata));
			Files.createFile(uploadDir.resolve("old-storage-key.zip"));

			ResponseEntity<String> response = controller.upload(
					                                            file,
					                                            new UploadMetadataRequest(id, "updated description", Set.of("updated"))
			                                                   )
			                                            .block(Duration.ofSeconds(5));

			assertNotNull(response);
			assertEquals(HttpStatus.CREATED, response.getStatusCode());
			var updated = org.mockito.ArgumentCaptor.forClass(MetadataDto.class);
			verify(uploadMetadataService).update(eq(oldMetadata), updated.capture());
			assertEquals(
					"replacement.txt",
					updated.getValue()
					       .name()
			            );
			assertEquals(
					"updated description",
					updated.getValue()
					       .description()
			            );
			assertEquals(
					Set.of("updated"),
					updated.getValue()
					       .tags()
			            );
			assertFalse(Files.exists(uploadDir.resolve("old-storage-key.zip")));
			assertArchiveContains(uploadDir.resolve(response.getBody() + ".zip"), contents);
			verify(uploadMetadataService, never()).create(any());
		}

		@Test
		void removesNewArchiveIfMetadataPersistenceFails() throws IOException {

			FilePart file = filePart("report.txt", "contents".getBytes());
			when(uploadMetadataService.create(any())).thenReturn(Mono.error(new IllegalStateException("metadata failed")));

			assertThrows(IllegalStateException.class, () -> controller.upload(
					                                                          file, new UploadMetadataRequest(null, "description", Set.of())
			                                                                 )
			                                                          .block(Duration.ofSeconds(5)));
			try (var files = Files.list(uploadDir)) {
				assertEquals(0, files.count());
			}
		}

		@Test
		void doesNotPersistMetadataWhenTransferFails() throws IOException {

			FilePart file = mock(FilePart.class);
			when(file.transferTo(any(Path.class))).thenReturn(Mono.error(new IOException("transfer failed")));

			assertThrows(RuntimeException.class, () -> controller.upload(
					                                                     file, new UploadMetadataRequest(null, "description", Set.of())
			                                                            )
			                                                     .block(Duration.ofSeconds(5)));
			verify(uploadMetadataService, never()).create(any());
			try (var files = Files.list(uploadDir)) {
				assertEquals(0, files.count());
			}
		}

		@Test
		void preservesReplacedArchiveWhenMetadataUpdateFails() throws IOException {

			UUID id = UUID.randomUUID();
			Files.writeString(uploadDir.resolve("old-storage-key.zip"), "old archive");
			Metadata oldMetadata = new Metadata();
			oldMetadata.setId(id);
			oldMetadata.setFileLocation("old-storage-key");
			when(uploadMetadataService.findById(id)).thenReturn(Mono.just(oldMetadata));
			doReturn(Mono.error(new IllegalStateException("metadata failed"))).when(uploadMetadataService).update(eq(oldMetadata), any());

			assertThrows(IllegalStateException.class, () -> controller.upload(
					                                                          filePart("replacement.txt", "contents".getBytes()),
					                                                          new UploadMetadataRequest(id, "description", Set.of())
			                                                                 )
			                                                          .block(Duration.ofSeconds(5)));
			assertEquals("old archive", Files.readString(uploadDir.resolve("old-storage-key.zip")));
			try (var files = Files.list(uploadDir)) {
				assertEquals(1, files.count());
			}
		}

	}

}
