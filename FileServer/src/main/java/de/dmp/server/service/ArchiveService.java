package de.dmp.server.service;

import com.github.f4b6a3.ulid.UlidCreator;
import de.dmp.server.data.dto.MetadataDto;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.codec.multipart.FilePart;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

@Slf4j
@Getter
public class ArchiveService implements Archive {

	private final static int max_threads = 2;
	private final Path uploadDir;
	private final ExecutorService executor;


	public ArchiveService(Path uploadDir) throws IOException {

		this.uploadDir = uploadDir;
		// Create the upload directory and any missing parent directories.
		Files.createDirectories(uploadDir);
		this.executor = Executors.newFixedThreadPool(max_threads);
	}

	@Override
	public Future<String> storeAsync(FilePart file) {

		return executor.submit(() -> store(file));
	}

	@Override
	public String store(FilePart file) throws IOException {

		String storageKey = UlidCreator.getUlid()
		                               .toString();
		Path destination = Files.createTempFile(uploadDir, "tmp", ".upload");
		try {
			file.transferTo(destination)
			    .block();
			Path archive = archivePath(storageKey);
			var output = Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW);
			try (output;
			     var zip = new ZipOutputStream(output)) {
				zip.putNextEntry(new ZipEntry(destination.getFileName()
				                                         .toString()));
				Files.copy(destination, zip);
				zip.closeEntry();
			} catch (IOException | RuntimeException ex) {
				try {
					Files.deleteIfExists(archive);
				} catch (IOException cleanup) {
					ex.addSuppressed(cleanup);
				}
				throw ex;
			}
			return storageKey;
		} finally {
			try {
				Files.deleteIfExists(destination);
			} catch (IOException ex) {
				log.warn("Failed to clean temporary upload {}", destination, ex);
			}
		}
	}

	@Override
	public void delete(String storageKey) throws IOException {

		Files.deleteIfExists(archivePath(storageKey));
	}

	@Override public Future<Optional<ArchivedFile>> retrieveAsync(MetadataDto metadataDto) {

		return executor.submit(() -> retrieve(metadataDto));
	}

	@Override public Optional<ArchivedFile> retrieve(MetadataDto metadataDto) throws IOException {

		final Path archive = archivePath(metadataDto.fileLocation());

		if (!Files.isRegularFile(archive)) {
			log.warn("Archive missing for file {}", metadataDto.id());
			return Optional.empty();
		}

		try (ZipFile zip = new ZipFile(archive.toFile())) {
			var entries = zip.entries();
			if (!entries.hasMoreElements()) return Optional.empty();
			ZipEntry entry = entries.nextElement();
			String entryName = entry.getName();
			return Optional.of(new ArchivedFile(
					new InputStreamResource(() -> openEntry(archive, entryName)), entry.getSize()
			));
		}
	}

	private InputStream openEntry(Path archive, String entryName) throws IOException {
		ZipFile zip = new ZipFile(archive.toFile());
		try {
			var entries = zip.entries();
			if (!entries.hasMoreElements()) throw new IOException("Archive entry is missing: " + entryName);
			ZipEntry entry = entries.nextElement();
			if (!entry.getName().equals(entryName)) throw new IOException("Archive entry has changed: " + entryName);
			return new FilterInputStream(zip.getInputStream(entry)) {
				@Override
				public void close() throws IOException {
					try {
						super.close();
					} finally {
						zip.close();
					}
				}
			};
		} catch (IOException | RuntimeException ex) {
			try {
				zip.close();
			} catch (IOException cleanup) {
				ex.addSuppressed(cleanup);
			}
			throw ex;
		}
	}

	private Path archivePath(String storageKey) throws IOException {

		Path root = uploadDir.toAbsolutePath()
		                     .normalize();
		Path archive = root.resolve("%s.zip".formatted(storageKey))
		                   .normalize();
		if (!root.equals(archive.getParent())) {
			throw new IOException("Invalid archive storage key");
		}
		return archive;
	}

	@Override
	public void close() {

		executor.shutdown();
	}

}