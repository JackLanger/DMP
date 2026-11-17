package de.dmp.server.configuration;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.BodyInserters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = FileServerSecurityConfigurationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.main.web-application-type=reactive",
                "spring.security.user.name=test-user",
                "spring.security.user.password=test-password",
                "dmp.fileserver.cors.allowed-origins=http://localhost:1420"
        })
class FileServerSecurityConfigurationTest {

    @Autowired
    private ApplicationContext context;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).configureClient()
                .baseUrl("http://fileserver.example").build();
    }

    @Test
    void browserLoginUsesSameOriginAndCsrfProtection() {
        var login = client.get().uri("/login").exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Referrer-Policy", "strict-origin-when-cross-origin")
                .expectBody(String.class).returnResult();
        String html = login.getResponseBody();
        assertNotNull(html);
        Matcher token = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
        assertTrue(token.find());
        var session = login.getResponseCookies().getFirst("SESSION");
        assertNotNull(session);

        client.post().uri("/login")
                .header("Origin", "null")
                .cookie("SESSION", session.getValue())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("username", "test-user")
                        .with("password", "test-password").with("_csrf", token.group(1)))
                .exchange().expectStatus().isForbidden();

        var result = client.post().uri("/login")
                .header("Origin", "http://fileserver.example")
                .cookie("SESSION", session.getValue())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("username", "test-user")
                        .with("password", "test-password").with("_csrf", token.group(1)))
                .exchange().expectStatus().is3xxRedirection().returnResult(Void.class);
        assertNotNull(result.getResponseHeaders().getLocation());
        assertEquals("/", result.getResponseHeaders().getLocation().getPath());
        var authenticatedSession = result.getResponseCookies().getFirst("SESSION");
        String sessionId = authenticatedSession == null ? session.getValue() : authenticatedSession.getValue();
        client.get().uri("/protected").cookie("SESSION", sessionId)
                .exchange().expectStatus().isOk();
        client.get().uri("/protected").exchange().expectStatus().isUnauthorized();
        client.get().uri("/protected")
                .headers(headers -> headers.setBasicAuth("test-user", "test-password"))
                .exchange().expectStatus().isOk();

        client.post().uri("/login")
                .header("Origin", "http://fileserver.example")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("username", "test-user").with("password", "test-password"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void corsStillRejectsOpaqueOriginsAndAllowsConfiguredFrontend() {
        client.options().uri("/login")
                .header("Origin", "null")
                .header("Access-Control-Request-Method", "POST")
                .exchange().expectStatus().isForbidden();

        client.options().uri("/login")
                .header("Origin", "http://localhost:1420")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,x-csrf-token")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:1420")
                .expectHeader().valueEquals("Access-Control-Allow-Credentials", "true");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({FileServerCorsConfiguration.class, FileServerSecurityConfiguration.class, ProtectedController.class})
    static class TestApplication {
    }

    @RestController
    static class ProtectedController {
        @GetMapping("/protected")
        String protectedResource() {
            return "ok";
        }
    }
}
