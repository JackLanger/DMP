package de.dmp.server.configuration;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Map;

@Configuration
public class ArchiveConfig {

	public ArchiveConfig() throws IOException {

		URL resource = this.getClass()
		                   .getClassLoader()
		                   .getResource("app.yaml");

		if (resource == null)
			throw new IOException("Resource not found: app.yaml");
	}

	@Bean
	public Archive archive() {

		try (var inputStream = this.getClass()
		                           .getClassLoader()
		                           .getResourceAsStream("app.yaml")) {

			Yaml yaml = new Yaml();
			Object configuration = yaml.load(inputStream);
			if (!(configuration instanceof Map<?, ?> values)
					|| !(values.get("uploadDir") instanceof String uploadDir)) {
				throw new IllegalStateException("app.yaml must define uploadDir as a string");
			}

			Path dir = Path.of(uploadDir);
			Archive archive = new Archive(dir);
			archive.setUploadDir(dir);
			return archive;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}


}
