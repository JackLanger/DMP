package de.dmp.server.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy;

@Configuration
public class FileServerSecurityConfiguration {

    @Bean
    Customizer<ServerHttpSecurity> fileServerReferrerPolicy() {
        return http -> http.headers(headers -> headers.referrerPolicy(
                policy -> policy.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)));
    }
}
