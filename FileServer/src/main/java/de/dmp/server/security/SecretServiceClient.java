package de.dmp.server.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.io.File;
import javax.net.ssl.SSLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import io.netty.handler.ssl.SslContextBuilder;
import reactor.netty.http.client.HttpClient;

@Component
public class SecretServiceClient {
    private final WebClient client;
    private final String serviceSecret;

    @Autowired
    public SecretServiceClient(
            @Value("${dmp.fileserver.secret-service.url}") String url,
            @Value("${dmp.fileserver.secret-service.credential}") String serviceSecret,
            @Value("${dmp.fileserver.secret-service.ca:}") String caPath) throws SSLException {
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("SecretService URL must be an HTTPS origin without credentials, path, query, or fragment");
        }
        if (serviceSecret.length() < 32) {
            throw new IllegalArgumentException("SecretService service credential must be at least 32 characters");
        }
        var builder = WebClient.builder().baseUrl(url);
        if (!caPath.isBlank()) {
            var sslContext = SslContextBuilder.forClient().trustManager(new File(caPath)).build();
            builder.clientConnector(new ReactorClientHttpConnector(
                    HttpClient.create().secure(ssl -> ssl.sslContext(sslContext))));
        }
        this.client = builder.build();
        this.serviceSecret = serviceSecret;
    }

    SecretServiceClient(WebClient client, String serviceSecret) {
        this.client = client;
        this.serviceSecret = serviceSecret;
    }

    public Mono<Principal> validate(String username, String token) {
        return client.post().uri("/v1/introspect")
                .header("X-Service-Secret", serviceSecret)
                .bodyValue(Map.of("username", username, "token", token))
                .exchangeToMono(response -> {
                    if (response.statusCode().equals(HttpStatus.UNAUTHORIZED)) {
                        return Mono.empty();
                    }
                    if (!response.statusCode().is2xxSuccessful()) {
                        return Mono.error(new UnavailableException());
                    }
                    return response.bodyToMono(Principal.class)
                            .filter(principal -> principal.userId() != null && !principal.userId().isBlank()
                                    && principal.username() != null && principal.username().equalsIgnoreCase(username))
                            .switchIfEmpty(Mono.error(new UnavailableException()));
                })
                .timeout(Duration.ofSeconds(5))
                .onErrorMap(error -> error instanceof UnavailableException ? error : new UnavailableException(error));
    }

    public record Principal(String userId, String username, List<String> groupIds) {
    }

    public static final class UnavailableException extends RuntimeException {
        public UnavailableException() {
            super("SecretService validation failed");
        }

        public UnavailableException(Throwable cause) {
            super("SecretService validation failed", cause);
        }
    }
}
