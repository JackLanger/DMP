package de.dmp.server.configuration;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Getter
@Setter
@NoArgsConstructor
public class Archive {

	private Path uploadDir;

	public Archive(Path uploadDir) throws IOException {

		this.uploadDir = uploadDir;

		// Create the upload directory and any missing parent directories.
		Files.createDirectories(uploadDir);
	}

}