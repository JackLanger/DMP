package de.dmp.server.configuration;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.Mockito.*;

@SpringBootTest(
        classes = FileServerSecurityConfigurationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "dmp.fileserver.cors.allowed-origins=http://localhost:1420")
class FileServerSecurityConfigurationTest {
    private static final String TOKEN = "a".repeat(43);
    @Autowired private ApplicationContext context;
    @MockitoBean private SecretServiceClient secrets;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).configureClient()
                .baseUrl("http://fileserver.example").build();
    }

    @Test
    void requiresValidUserAndTokenOnEveryRequest() {
        when(secrets.validate("alice", TOKEN))
                .thenReturn(Mono.just(new SecretServiceClient.Principal("id", "alice", List.of())));
        when(secrets.validate("bob", TOKEN)).thenReturn(Mono.empty());

        client.get().uri("/protected").exchange().expectStatus().isUnauthorized();
        client.get().uri("/protected").headers(h -> h.setBasicAuth("dev", "change-me"))
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/protected").header("X-User", "bob").headers(h -> h.setBearerAuth(TOKEN))
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/protected").header("X-User", "alice").headers(h -> h.setBearerAuth(TOKEN))
                .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("alice");
        client.post().uri("/protected").header("X-User", "alice").headers(h -> h.setBearerAuth(TOKEN))
                .exchange().expectStatus().isOk();
        verify(secrets, times(2)).validate("alice", TOKEN);
    }

    @Test
    void failsClosedWhenValidationUnavailable() {
        when(secrets.validate("alice", TOKEN)).thenReturn(Mono.error(new SecretServiceClient.UnavailableException()));
        client.get().uri("/protected").header("X-User", "alice").headers(h -> h.setBearerAuth(TOKEN))
                .exchange().expectStatus().isEqualTo(503);
    }

    @Test
    void doesNotCacheAuthenticationOrAcceptAmbiguousHeaders() {
        when(secrets.validate("alice", TOKEN))
                .thenReturn(Mono.just(new SecretServiceClient.Principal("id", "alice", List.of())), Mono.empty());
        client.get().uri("/protected").header("X-User", "alice").headers(h -> h.setBearerAuth(TOKEN))
                .exchange().expectStatus().isOk();
        client.get().uri("/protected").header("X-User", "alice").headers(h -> h.setBearerAuth(TOKEN))
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/protected").header("X-User", "alice", "bob").headers(h -> h.setBearerAuth(TOKEN))
                .exchange().expectStatus().isUnauthorized();
        verify(secrets, times(2)).validate("alice", TOKEN);
    }

    @Test
    void corsRejectsOpaqueOriginsAndAllowsTransferHeaders() {
        client.options().uri("/protected").header("Origin", "null")
                .header("Access-Control-Request-Method", "POST").exchange().expectStatus().isForbidden();
        client.options().uri("/protected").header("Origin", "http://localhost:1420")
                .header("Access-Control-Request-Method", "PATCH")
                .header("Access-Control-Request-Headers", "authorization,x-user,content-range")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:1420");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({FileServerCorsConfiguration.class, FileServerSecurityConfiguration.class, ProtectedController.class})
    static class TestApplication {
    }

    @RestController
    static class ProtectedController {
        @GetMapping("/protected")
        Mono<String> protectedResource(java.security.Principal principal) {
            return Mono.just(((SecretServiceClient.Principal)
                    ((org.springframework.security.core.Authentication) principal).getPrincipal()).username());
        }

        @PostMapping("/protected")
        String update() {
            return "ok";
        }
    }
}
