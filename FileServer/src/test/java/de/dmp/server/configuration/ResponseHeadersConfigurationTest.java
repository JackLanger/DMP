package de.dmp.server.configuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@SpringBootTest(
        classes = ResponseHeadersConfigurationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "dmp.fileserver.cors.allowed-origins=http://localhost:1420")
class ResponseHeadersConfigurationTest {
    @Autowired private ApplicationContext context;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).configureClient()
                .baseUrl("http://fileserver.example").build();
    }

    @Test
    void requestsNeedNoCredentialsAndIgnoreUserHeaders() {
        client.get().uri("/resource").exchange().expectStatus().isOk()
                .expectBody(String.class).isEqualTo("no principal");
        client.get().uri("/resource").header("X-User", "alice", "bob")
                .headers(h -> h.setBearerAuth("invalid"))
                .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("no principal");
        client.post().uri("/resource").exchange().expectStatus().isOk();
        client.get().uri("/login").exchange().expectStatus().isNotFound();
    }

    @Test
    void preservesSecurityHeadersAndExplicitCachePolicy() {
        client.get().uri("/resource").exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("X-Frame-Options", "DENY")
                .expectHeader().valueEquals("X-XSS-Protection", "0")
                .expectHeader().valueEquals("Referrer-Policy", "strict-origin-when-cross-origin")
                .expectHeader().valueEquals("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate")
                .expectHeader().doesNotExist("Strict-Transport-Security");
        client.get().uri("https://fileserver.example/resource").exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains");
        client.get().uri("/cached").exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "max-age=60");
        client.get().uri("/missing").exchange().expectStatus().isNotFound()
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff");
    }

    @Test
    void corsRejectsOpaqueOriginsAndAllowsTransferHeaders() {
        client.options().uri("/resource").header("Origin", "null")
                .header("Access-Control-Request-Method", "POST").exchange().expectStatus().isForbidden()
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff");
        client.options().uri("/resource").header("Origin", "http://localhost:1420")
                .header("Access-Control-Request-Method", "PATCH")
                .header("Access-Control-Request-Headers", "authorization,x-user,content-range")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:1420")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({FileServerCorsConfiguration.class, ResponseHeadersConfiguration.class, TestController.class})
    static class TestApplication {
    }

    @RestController
    static class TestController {
        @GetMapping("/resource")
        Mono<String> resource(ServerWebExchange exchange) {
            return exchange.getPrincipal().map(principal -> "principal present").defaultIfEmpty("no principal");
        }

        @PostMapping("/resource")
        String update() {
            return "ok";
        }

        @GetMapping("/cached")
        ResponseEntity<String> cached() {
            return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofSeconds(60))).body("ok");
        }
    }
}
