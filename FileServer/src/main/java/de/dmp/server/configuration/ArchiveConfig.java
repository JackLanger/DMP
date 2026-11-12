package de.dmp.server.configuration;


import de.dmp.server.service.archive.Archive;
import de.dmp.server.service.archive.ArchiveService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.nio.file.Path;

@Configuration
@EnableScheduling
public class ArchiveConfig {


	@Bean(destroyMethod = "close")
	public Archive archive(
			@Value("${archive.upload:data/archive}") String uploadDir,
			@Value("${archive.workers:2}") int workers) {

		try {

			Path dir = Path.of(uploadDir);
			return new ArchiveService(dir, workers);

		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}


}
