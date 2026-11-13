package de.dmp.server.configuration;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSizeExceptionHandler implements WebExceptionHandler {

	@Override
	public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
		MediaType contentType = exchange.getRequest().getHeaders().getContentType();
		if (contentType == null || !MediaType.MULTIPART_FORM_DATA.isCompatibleWith(contentType)) {
			return Mono.error(error);
		}
		Throwable cause = error;
		while (cause != null && !(cause instanceof DataBufferLimitException)) {
			cause = cause.getCause();
		}
		if (cause == null || exchange.getResponse().isCommitted()) {
			return Mono.error(error);
		}
		exchange.getResponse().setStatusCode(HttpStatus.CONTENT_TOO_LARGE);
		exchange.getResponse().getHeaders().setContentType(MediaType.TEXT_PLAIN);
		byte[] message = "Request exceeds the configured size limit".getBytes(StandardCharsets.UTF_8);
		return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(message)));
	}
}
