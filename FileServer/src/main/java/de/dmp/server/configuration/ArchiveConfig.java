package de.dmp.server.configuration;


import de.dmp.server.service.Archive;
import de.dmp.server.service.ArchiveService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;

@Configuration
public class ArchiveConfig {

	public ArchiveConfig() throws IOException {

		URL resource = this.getClass()
		                   .getClassLoader()
		                   .getResource("app.yaml");

		if (resource == null)
			throw new IOException("Resource not found: app.yaml");
	}

	@Bean(destroyMethod = "close")
	public Archive archive() {

		try (var inputStream = this.getClass()
		                           .getClassLoader()
		                           .getResourceAsStream("app.yaml")) {

			ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
			JsonNode configuration = mapper.readTree(inputStream);
			JsonNode uploadDirNode = configuration == null ? null : configuration.get("uploadDir");
			if (uploadDirNode == null || !uploadDirNode.isString()) {
				throw new IllegalStateException("app.yaml must define uploadDir as a string");
			}

			String uploadDir = uploadDirNode.stringValue();
			Path dir = Path.of(uploadDir);
			return new ArchiveService(dir);

		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}


}
