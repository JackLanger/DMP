package de.dmp.server.configuration;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.webflux.autoconfigure.ReactiveMultipartProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MultipartLimitConfiguration {

	@Bean
	InitializingBean validateMultipartLimits(ReactiveMultipartProperties properties) {
		return () -> {
			long diskLimit = properties.getMaxDiskUsagePerPart().toBytes();
			long memoryLimit = properties.getMaxInMemorySize().toBytes();
			if (diskLimit < -1 || (diskLimit >= 0 && (memoryLimit < 0 || diskLimit < memoryLimit))) {
				throw new IllegalArgumentException("Multipart disk limit must be -1 or at least the in-memory limit");
			}
		};
	}
}
