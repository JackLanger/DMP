package de.dmp.server.configuration;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.nio.file.Path;

@Getter
@Setter
@NoArgsConstructor
public class Archive {

	private Path uploadDir;

	public Archive(Path uploadDir) {

		this.uploadDir = uploadDir;
	}

}