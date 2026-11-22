package de.dmp.server.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

@Configuration
public class ResponseHeadersConfiguration {
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    WebFilter responseHeadersWebFilter() {
        return (exchange, chain) -> {
            exchange.getResponse().beforeCommit(() -> {
                HttpHeaders headers = exchange.getResponse().getHeaders();
                if (headers.getFirst(HttpHeaders.CACHE_CONTROL) == null) {
                    headers.setCacheControl("no-cache, no-store, max-age=0, must-revalidate");
                    headers.setPragma("no-cache");
                    headers.setExpires(0);
                }
                headers.putIfAbsent("X-Content-Type-Options", java.util.List.of("nosniff"));
                headers.putIfAbsent("X-Frame-Options", java.util.List.of("DENY"));
                headers.putIfAbsent("X-XSS-Protection", java.util.List.of("0"));
                headers.putIfAbsent("Referrer-Policy", java.util.List.of("strict-origin-when-cross-origin"));
                if ("https".equalsIgnoreCase(exchange.getRequest().getURI().getScheme())) {
                    headers.putIfAbsent("Strict-Transport-Security", java.util.List.of("max-age=31536000 ; includeSubDomains"));
                }
                return Mono.empty();
            });
            return chain.filter(exchange);
        };
    }
}
