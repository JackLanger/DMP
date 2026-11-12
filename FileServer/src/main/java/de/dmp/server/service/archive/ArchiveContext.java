package de.dmp.server.service.archive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public record ArchiveContext(Path uploadDir) {

	public ArchiveContext {
		Objects.requireNonNull(uploadDir, "uploadDir");
	}

	public static ArchiveContext create(Path uploadDir) throws IOException {
		Files.createDirectories(uploadDir);
		return new ArchiveContext(uploadDir);
	}

	public Path archivePath(String storageKey) throws IOException {
		Path root = uploadDir.toAbsolutePath().normalize();
		Path archive = root.resolve("%s.zip".formatted(storageKey)).normalize();
		if (!root.equals(archive.getParent())) {
			throw new IOException("Invalid archive storage key");
		}
		return archive;
	}
}
