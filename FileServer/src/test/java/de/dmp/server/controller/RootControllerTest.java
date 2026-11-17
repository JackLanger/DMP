package de.dmp.server.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
		classes = RootControllerTest.TestApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.MOCK,
		properties = {
				"spring.main.web-application-type=reactive",
				"spring.security.user.name=test-user",
				"spring.security.user.password=test-password",
				"spring.autoconfigure.exclude=org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration,"
						+ "org.springframework.boot.mongodb.autoconfigure.MongoReactiveAutoConfiguration,"
						+ "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration,"
						+ "org.springframework.boot.data.mongodb.autoconfigure.DataMongoReactiveAutoConfiguration"
		})
class RootControllerTest {

	@Autowired
	private ApplicationContext context;
	private WebTestClient client;

	@BeforeEach
	void setUp() {
		client = WebTestClient.bindToApplicationContext(context).build();
	}

	@Test
	void rootRequiresAuthentication() {
		client.get().uri("/").exchange().expectStatus().isUnauthorized();
	}

	@Test
	void authenticatedRootRedirectsToFiles() {
		client.get().uri("/")
				.headers(headers -> headers.setBasicAuth("test-user", "test-password"))
				.exchange().expectStatus().isFound()
				.expectHeader().location("/files/all");
	}

	@Test
	void formLoginReturnsToRootThenRedirectsToFiles() {
		var login = client.get().uri("/login").exchange().expectStatus().isOk()
				.expectBody(String.class).returnResult();
		String html = login.getResponseBody();
		assertNotNull(html);
		Matcher token = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
		assertTrue(token.find());
		var session = login.getResponseCookies().getFirst("SESSION");
		assertNotNull(session);

		var result = client.post().uri("/login")
				.cookie("SESSION", session.getValue())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(BodyInserters.fromFormData("username", "test-user")
						.with("password", "test-password").with("_csrf", token.group(1)))
				.exchange().expectStatus().is3xxRedirection().returnResult(Void.class);

		URI location = result.getResponseHeaders().getLocation();
		assertNotNull(location);
		assertEquals("/", location.getPath());
		var authenticatedSession = result.getResponseCookies().getFirst("SESSION");
		String sessionId = authenticatedSession == null ? session.getValue() : authenticatedSession.getValue();
		client.get().uri("/").cookie("SESSION", sessionId)
				.exchange().expectStatus().isFound().expectHeader().location("/files/all");
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@Import(RootController.class)
	static class TestApplication {
	}
}
