package de.dmp.server.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

@SpringBootTest(
		classes = MultipartUploadLimitTest.TestApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.MOCK,
		properties = {
				"spring.config.location=file:src/main/resources/application.yml",
				"FILESERVER_MULTIPART_MAX_DISK_USAGE_PER_PART=1MB",
				"spring.autoconfigure.exclude=org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration,"
						+ "org.springframework.boot.mongodb.autoconfigure.MongoReactiveAutoConfiguration,"
						+ "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration,"
						+ "org.springframework.boot.data.mongodb.autoconfigure.DataMongoReactiveAutoConfiguration,"
						+ "org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration,"
						+ "org.springframework.boot.security.autoconfigure.actuate.web.reactive.ReactiveManagementWebSecurityAutoConfiguration"
		})
class MultipartUploadOverrideTest {

	@Autowired
	private ApplicationContext context;

	@Test
	void appliesConfiguredDiskLimit() {
		WebTestClient client = WebTestClient.bindToApplicationContext(context).configureClient()
				.responseTimeout(Duration.ofMinutes(2)).build();
		MultipartUploadLimitTest.upload(client, 1024 * 1024).expectStatus().isCreated();
		MultipartUploadLimitTest.upload(client, 1024 * 1024 + 1).expectStatus().isEqualTo(413);
	}
}
