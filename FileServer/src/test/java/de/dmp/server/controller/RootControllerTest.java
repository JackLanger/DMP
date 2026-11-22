package de.dmp.server.controller;

import de.dmp.server.configuration.FileServerSecurityConfiguration;
import de.dmp.server.security.SecretServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.Mockito.when;

@SpringBootTest(classes = RootControllerTest.TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class RootControllerTest {
    @Autowired private ApplicationContext context;
    @MockitoBean private SecretServiceClient secrets;
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
        String token = "a".repeat(43);
        when(secrets.validate("alice", token))
                .thenReturn(Mono.just(new SecretServiceClient.Principal("id", "alice", List.of())));
        client.get().uri("/").header("X-User", "alice").headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isFound().expectHeader().location("/files/all");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({RootController.class, FileServerSecurityConfiguration.class})
    static class TestApplication {
    }
}
