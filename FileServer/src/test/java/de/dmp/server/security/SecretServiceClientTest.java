package de.dmp.server.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class SecretServiceClientTest {
    private SecretServiceClient client(HttpStatus status, String body) {
        WebClient web = WebClient.builder().baseUrl("https://secret.example")
                .exchangeFunction(request -> {
                    assertEquals("service-secret", request.headers().getFirst("X-Service-Secret"));
                    assertEquals("/v1/introspect", request.url().getPath());
                    return Mono.just(ClientResponse.create(status)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body).build());
                }).build();
        return new SecretServiceClient(web, "service-secret");
    }

    @Test
    void acceptsMatchingPrincipalAndRejectsInvalidToken() {
        var principal = client(HttpStatus.OK, "{\"userId\":\"id\",\"username\":\"alice\",\"groupIds\":[]}")
                .validate("Alice", "token").block(Duration.ofSeconds(2));
        assertNotNull(principal);
        assertEquals("id", principal.userId());
        assertNull(client(HttpStatus.UNAUTHORIZED, "").validate("alice", "bad").block(Duration.ofSeconds(2)));
    }

    @Test
    void failsClosedForServiceFailuresAndInvalidResponses() {
        for (var c : new SecretServiceClient[] {
                client(HttpStatus.FORBIDDEN, ""),
                client(HttpStatus.INTERNAL_SERVER_ERROR, ""),
                client(HttpStatus.OK, "{}"),
                client(HttpStatus.OK, "{\"userId\":\"id\",\"username\":\"other\"}"),
                client(HttpStatus.OK, "not json")
        }) {
            assertThrows(SecretServiceClient.UnavailableException.class,
                    () -> c.validate("alice", "token").block(Duration.ofSeconds(2)));
        }
    }

    @Test
    void refusesInsecureConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new SecretServiceClient("http://secret.example", "a".repeat(32), ""));
        assertThrows(IllegalArgumentException.class,
                () -> new SecretServiceClient("https://secret.example", "short", ""));
        assertThrows(IllegalArgumentException.class,
                () -> new SecretServiceClient("https://secret.example/prefix", "a".repeat(32), ""));
    }
}
