package de.dmp.server.configuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;

@SpringBootTest(
		classes = MultipartUploadLimitTest.TestApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.MOCK,
		properties = {
				"spring.config.location=file:src/main/resources/application.yml",
				"spring.autoconfigure.exclude=org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration,"
						+ "org.springframework.boot.mongodb.autoconfigure.MongoReactiveAutoConfiguration,"
						+ "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration,"
						+ "org.springframework.boot.data.mongodb.autoconfigure.DataMongoReactiveAutoConfiguration,"
						+ "org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration,"
						+ "org.springframework.boot.security.autoconfigure.actuate.web.reactive.ReactiveManagementWebSecurityAutoConfiguration"
		})
class MultipartUploadLimitTest {

	private static final long LIMIT = 100L * 1024 * 1024;

	@Autowired
	private org.springframework.context.ApplicationContext context;

	private WebTestClient client;

	@BeforeEach
	void setUp() {
		client = WebTestClient.bindToApplicationContext(context).configureClient()
				.responseTimeout(Duration.ofMinutes(2)).build();
	}

	@Test
	void acceptsMultipartFileAtConfiguredLimit() {
		upload(client, LIMIT).expectStatus().isCreated();
	}

	@Test
	void rejectsMultipartFileAboveConfiguredLimit() {
		upload(client, LIMIT + 1).expectStatus().isEqualTo(413)
				.expectBody(String.class).isEqualTo("Request exceeds the configured size limit");
	}

	@Test
	void mapsWrappedSizeErrorsAndPreservesOtherServerErrors() {
		client.get().uri("/test-wrapped-limit").header("Content-Type", MediaType.MULTIPART_FORM_DATA_VALUE)
				.exchange().expectStatus().isEqualTo(413);
		client.get().uri("/test-wrapped-limit").exchange().expectStatus().is5xxServerError();
		client.get().uri("/test-error").exchange().expectStatus().is5xxServerError();
	}

	static WebTestClient.ResponseSpec upload(WebTestClient client, long size) {
		MultipartBodyBuilder body = new MultipartBodyBuilder();
		body.part("file", new InputStreamResource(new ZeroInputStream(size)) {
			@Override
			public String getFilename() {
				return "test.bin";
			}

			@Override
			public long contentLength() {
				return size;
			}
		});
		return client.post().uri("/test-upload")
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.body(BodyInserters.fromMultipartData(body.build()))
				.exchange();
	}

	private static final class ZeroInputStream extends InputStream {
		private long remaining;

		private ZeroInputStream(long remaining) {
			this.remaining = remaining;
		}

		@Override
		public int read() {
			if (remaining == 0) return -1;
			remaining--;
			return 0;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) {
			if (length == 0) return 0;
			if (remaining == 0) return -1;
			int count = (int) Math.min(remaining, length);
			Arrays.fill(buffer, offset, offset + count, (byte) 0);
			remaining -= count;
			return count;
		}
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@Import({MultipartLimitConfiguration.class, RequestSizeExceptionHandler.class, TestController.class})
	static class TestApplication {
	}

	@RestController
	static class TestController {
		@PostMapping(path = "/test-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
		@ResponseStatus(HttpStatus.CREATED)
		Mono<Void> upload(@RequestPart("file") FilePart file) {
			return file.content().doOnNext(DataBufferUtils::release).then();
		}

		@GetMapping("/test-wrapped-limit")
		Mono<Void> wrappedLimit() {
			return Mono.error(new IllegalStateException(new DataBufferLimitException("limit")));
		}

		@GetMapping("/test-error")
		Mono<Void> error() {
			return Mono.error(new IllegalStateException("unrelated error"));
		}
	}
}
