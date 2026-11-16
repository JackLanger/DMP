package de.dmp.server;

import de.dmp.server.data.dto.MetadataDto;
import de.dmp.server.controller.FileController;
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
		registry.add("spring.security.user.name", () -> "test");
		registry.add("spring.security.user.password", () -> "test-password");
		registry.add("spring.grpc.server.port", () -> "0");
		registry.add("dmp.fileserver.cors.allowed-origins", () -> "http://localhost:1420");
	}

	@Autowired ReactiveMetadataService service;
	@Autowired FileController controller;
	@LocalServerPort int port;

	@Test
	void servesStoredMetadataThroughHttp() {
		MetadataDto dto = new MetadataDto("file.txt", "description", "key", Set.of("tag"));
		service.create(dto).block(Duration.ofSeconds(10));
		WebTestClient client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
				.defaultHeaders(headers -> headers.setBasicAuth("test", "test-password")).build();
		client.get().uri("/files/{id}", dto.id()).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.id").isEqualTo(dto.id().toString())
				.jsonPath("$.name").isEqualTo("file.txt")
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
		body.part("metadata", "{\"description\":\"description\",\"tags\":[\"upload\"]}")
				.contentType(MediaType.APPLICATION_JSON);
		WebTestClient client = WebTestClient.bindToController(controller).build();
		client.post().uri("/files").contentType(MediaType.MULTIPART_FORM_DATA)
				.body(BodyInserters.fromMultipartData(body.build())).exchange().expectStatus().isCreated();
		MetadataDto stored = service.findByTagName("upload").blockFirst(Duration.ofSeconds(10));
		org.junit.jupiter.api.Assertions.assertNotNull(stored);
		client.get().uri("/files/{id}/download", stored.id()).exchange().expectStatus().isOk()
				.expectBody(byte[].class).isEqualTo(contents);
	}
}
