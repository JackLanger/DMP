package de.dmp.server.configuration;

import de.dmp.server.security.SecretServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy;
import org.springframework.web.server.WebFilter;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Configuration
public class FileServerSecurityConfiguration {
    private static final Logger log = LoggerFactory.getLogger(FileServerSecurityConfiguration.class);
    private static final Pattern USERNAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{2,63}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    @Bean
    SecurityWebFilterChain fileServerSecurity(ServerHttpSecurity http, SecretServiceClient secrets) {
        WebFilter tokenFilter = (exchange, chain) -> {
            String username = exchange.getRequest().getHeaders().getFirst("X-User");
            String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (username == null || !USERNAME.matcher(username).matches() || authorization == null
                    || !authorization.startsWith("Bearer ")
                    || !TOKEN.matcher(authorization.substring(7)).matches()
                    || exchange.getRequest().getHeaders().getOrEmpty("X-User").size() != 1
                    || exchange.getRequest().getHeaders().getOrEmpty(HttpHeaders.AUTHORIZATION).size() != 1) {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }
            return secrets.validate(username, authorization.substring(7))
                    .map(Optional::of)
                    .defaultIfEmpty(Optional.empty())
                    .flatMap(principal -> {
                        if (principal.isEmpty()) {
                            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                            return exchange.getResponse().setComplete();
                        }
                        var authentication = new UsernamePasswordAuthenticationToken(principal.get(), null, List.of());
                        return chain.filter(exchange)
                                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
                    })
                    .onErrorResume(SecretServiceClient.UnavailableException.class, error -> {
                        log.warn("SecretService token validation is unavailable");
                        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                        return exchange.getResponse().setComplete();
                    });
        };
        return http
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .headers(headers -> headers.referrerPolicy(
                        policy -> policy.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
                .authorizeExchange(exchanges -> exchanges.anyExchange().authenticated())
                .addFilterAt(tokenFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }
}
