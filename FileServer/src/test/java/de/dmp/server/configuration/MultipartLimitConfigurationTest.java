package de.dmp.server.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.webflux.autoconfigure.ReactiveMultipartProperties;
import org.springframework.util.unit.DataSize;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MultipartLimitConfigurationTest {

	@Test
	void rejectsDiskLimitBelowMemoryThreshold() {
		ReactiveMultipartProperties properties = new ReactiveMultipartProperties();
		properties.setMaxDiskUsagePerPart(DataSize.ofKilobytes(128));
		assertThrows(IllegalArgumentException.class,
				() -> new MultipartLimitConfiguration().validateMultipartLimits(properties).afterPropertiesSet());
	}

	@Test
	void acceptsDiskLimitAtMemoryThresholdOrUnlimited() {
		ReactiveMultipartProperties properties = new ReactiveMultipartProperties();
		properties.setMaxDiskUsagePerPart(properties.getMaxInMemorySize());
		assertDoesNotThrow(() -> new MultipartLimitConfiguration().validateMultipartLimits(properties).afterPropertiesSet());
		properties.setMaxDiskUsagePerPart(DataSize.ofBytes(-1));
		assertDoesNotThrow(() -> new MultipartLimitConfiguration().validateMultipartLimits(properties).afterPropertiesSet());
	}
}
