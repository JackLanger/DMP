package de.dmp.server.service.archive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ArchiveContext(Path uploadDir, List<ArchiveStorageStrategy> strategies) {

	public ArchiveContext {
		Objects.requireNonNull(uploadDir, "uploadDir");
		strategies = List.copyOf(strategies);
		if (strategies.isEmpty()) throw new IllegalArgumentException("At least one storage strategy is required");
		var extensions = new HashSet<String>();
		for (var strategy : strategies) {
			String extension = strategy.extension();
			if (extension == null || !extension.matches("\\.[a-zA-Z0-9_-]+") || !extensions.add(extension.toLowerCase(java.util.Locale.ROOT))) {
				throw new IllegalArgumentException("Storage strategy extensions must be unique and valid");
			}
		}
	}

	public ArchiveContext(Path uploadDir) {
		this(uploadDir, List.of(new ZipArchiveStorageStrategy()));
	}

	public static ArchiveContext create(Path uploadDir) throws IOException {
		Files.createDirectories(uploadDir);
		return new ArchiveContext(uploadDir);
	}

	public static ArchiveContext create(Path uploadDir, List<ArchiveStorageStrategy> strategies) throws IOException {
		Files.createDirectories(uploadDir);
		return new ArchiveContext(uploadDir, strategies);
	}

	public ArchiveStorageStrategy strategyFor(Path source, String filename) throws IOException {
		return strategies.stream().filter(strategy -> strategy.supports(source, filename)).findFirst()
				.orElseThrow(() -> new IOException("No storage strategy supports the input"));
	}

	public Path archivePath(String storageKey, ArchiveStorageStrategy strategy) throws IOException {
		Path root = uploadDir.toAbsolutePath().normalize();
		Path archive = root.resolve(storageKey + strategy.extension()).normalize();
		if (!root.equals(archive.getParent())) {
			throw new IOException("Invalid archive storage key");
		}
		return archive;
	}

	public Optional<StoredArchive> findArchive(String storageKey) throws IOException {
		StoredArchive found = null;
		for (var strategy : strategies) {
			Path path = archivePath(storageKey, strategy);
			if (Files.exists(path)) {
				if (found != null) throw new IOException("Multiple archive formats exist for storage key: " + storageKey);
				found = new StoredArchive(path, strategy);
			}
		}
		return Optional.ofNullable(found);
	}

	public record StoredArchive(Path path, ArchiveStorageStrategy strategy) { }
}
