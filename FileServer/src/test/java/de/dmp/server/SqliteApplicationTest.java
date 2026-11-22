package de.dmp.server;

import de.dmp.server.data.dto.MetadataDto;
import de.dmp.server.service.ReactiveMetadataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.BodyInserters;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SqliteApplicationTest {
	@TempDir static Path directory;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("metadata.database", () -> directory.resolve("metadata.db").toString());
		registry.add("archive.upload", () -> directory.resolve("archive").toString());
		registry.add("spring.grpc.server.port", () -> "0");
		registry.add("dmp.fileserver.cors.allowed-origins", () -> "http://localhost:1420");
	}

	@Autowired ReactiveMetadataService service;
	@LocalServerPort int port;

	@Test
	void servesStoredMetadataThroughHttp() {
		MetadataDto dto = new MetadataDto("file.txt", "description", "key", "FILE", Set.of("tag"));
		service.create(dto).block(Duration.ofSeconds(10));
		WebTestClient client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
		client.get().uri("/files/{id}", dto.id()).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.id").isEqualTo(dto.id().toString())
				.jsonPath("$.name").isEqualTo("file.txt")
				.jsonPath("$.fileType").isEqualTo("FILE")
				.jsonPath("$.tags[0]").isEqualTo("tag");
	}

	@Test
	void uploadsAndDownloadsUsingTheRealArchiveAndSqliteRepository() {
		byte[] contents = "SQLite upload".getBytes(StandardCharsets.UTF_8);
		MultipartBodyBuilder body = new MultipartBodyBuilder();
		body.part("file", new ByteArrayResource(contents) {
			@Override public String getFilename() {
				return "upload.txt";
			}
		});
		body.part("metadata", "{\"description\":\"description\",\"filetype\":\"ARCHIVE\",\"tags\":[\"upload\"]}")
				.contentType(MediaType.APPLICATION_JSON);
		WebTestClient client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
		client.post().uri("/files").contentType(MediaType.MULTIPART_FORM_DATA)
				.body(BodyInserters.fromMultipartData(body.build())).exchange().expectStatus().isCreated();
		MetadataDto stored = service.findByTagName("upload").blockFirst(Duration.ofSeconds(10));
		org.junit.jupiter.api.Assertions.assertNotNull(stored);
		org.junit.jupiter.api.Assertions.assertEquals("ARCHIVE", stored.fileType());
		client.get().uri("/files/{id}/download", stored.id()).exchange().expectStatus().isOk()
				.expectBody(byte[].class).isEqualTo(contents);
	}

	@Test
	void managesResumableTransfersWithoutCredentials() {
		WebTestClient client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
		String location = client.post().uri("/files/transfers")
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"filename\":\"test.txt\",\"size\":3,\"fileType\":\"FILE\",\"sha256\":\"" + "0".repeat(64) + "\"}")
				.exchange().expectStatus().isCreated().returnResult(Void.class)
				.getResponseHeaders().getFirst("Location");
		org.junit.jupiter.api.Assertions.assertNotNull(location);
		client.patch().uri(location).contentType(MediaType.APPLICATION_OCTET_STREAM)
				.header("Content-Range", "bytes 0-2/3").bodyValue("abc".getBytes(StandardCharsets.UTF_8))
				.exchange().expectStatus().isNoContent().expectHeader().valueEquals("Upload-Offset", "3");
		client.delete().uri(location).exchange().expectStatus().isNoContent();
	}

	@Test
	void persistsResumableTransferFileType() throws Exception {
		byte[] contents = "typed transfer".getBytes(StandardCharsets.UTF_8);
		String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(contents));
		WebTestClient client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
		String location = client.post().uri("/files/transfers").contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"filename\":\"typed.txt\",\"size\":" + contents.length + ",\"sha256\":\"" + hash
						+ "\",\"description\":\"description\",\"fileType\":\"directory\"}")
				.exchange().expectStatus().isCreated().returnResult(Void.class).getResponseHeaders().getFirst("Location");
		org.junit.jupiter.api.Assertions.assertNotNull(location);
		client.patch().uri(location).contentType(MediaType.APPLICATION_OCTET_STREAM)
				.header("Content-Range", "bytes 0-" + (contents.length - 1) + "/" + contents.length)
				.bodyValue(contents).exchange().expectStatus().isNoContent();
		client.post().uri(location + "/complete").exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.documentId").isNotEmpty();
		client.get().uri("/files/{id}", location.substring(location.lastIndexOf('/') + 1)).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.fileType").isEqualTo("DIRECTORY");
	}

	@Test
	void rejectsMissingOrInvalidUploadFileType() {
		WebTestClient client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
		for (String field : new String[] {"", ",\"fileType\":\"image/png\""}) {
			client.post().uri("/files/transfers").contentType(MediaType.APPLICATION_JSON)
					.bodyValue("{\"filename\":\"test.txt\",\"size\":0,\"sha256\":\"" + "0".repeat(64) + "\"" + field + "}")
					.exchange().expectStatus().isBadRequest();
			MultipartBodyBuilder body = new MultipartBodyBuilder();
			body.part("file", new ByteArrayResource(new byte[] {1}) {
				@Override public String getFilename() { return "test.txt"; }
			});
			body.part("metadata", "{\"description\":\"description\"" + field + "}").contentType(MediaType.APPLICATION_JSON);
			client.post().uri("/files").contentType(MediaType.MULTIPART_FORM_DATA)
					.body(BodyInserters.fromMultipartData(body.build())).exchange().expectStatus().isBadRequest();
		}
	}
}
