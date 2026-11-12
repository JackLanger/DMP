package de.dmp.server.service;

import de.dmp.server.data.dto.MetadataDto;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipFile;

@Slf4j
@Getter
public class ArchiveService implements Archive {

	private final static int max_threads = 2;
	private final Path uploadDir;
	private final ExecutorService executor;


	public ArchiveService(Path uploadDir) throws IOException {

		this.uploadDir = uploadDir;
		this.executor = Executors.newFixedThreadPool(max_threads);

		// Create the upload directory and any missing parent directories.
		Files.createDirectories(uploadDir);
	}

	private static void deleteTempFile(Path destination) {

		if (!destination.toFile()
		                .delete()) log.debug("Failed to clean File {}", destination.getFileName());
	}

	private static void deleteArchive(Path uploadDir, String storageKey) {

		try {
			Files.deleteIfExists(uploadDir.resolve("%s.zip".formatted(storageKey)));
		} catch (IOException ex) {
			log.warn("Failed to delete archive for {}", storageKey, ex);
		}
	}

	@Override public Future<ZipFile> retrieveAsync(MetadataDto metadataDto) {


		return executor.submit(() -> {
			try {
				return retrieve(metadataDto).orElse(null);
			} catch (IOException e) {
				log.error("Error retrieving archive for file {}", metadataDto.id(), e);
				return null;
			}
		});
	}

	@Override public Optional<ZipFile> retrieve(MetadataDto metadataDto) throws IOException {

		final Path root = this.getUploadDir()
		                      .toAbsolutePath()
		                      .normalize();

		final Path archive = root.resolve("%s.tar.gz".formatted(metadataDto.fileLocation()))
		                         .normalize();

		if (!archive.startsWith(root) || !Files.isRegularFile(archive)) {
			log.warn("Archive missing for file {}", metadataDto.id());
			return Optional.empty();
		}

		return Optional.of(new ZipFile(archive.toFile()));
	}


}