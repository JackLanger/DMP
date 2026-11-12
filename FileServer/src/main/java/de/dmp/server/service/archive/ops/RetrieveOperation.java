package de.dmp.server.service.archive.ops;

import de.dmp.server.data.dto.MetadataDto;
import de.dmp.server.service.archive.ArchiveContext;
import de.dmp.server.service.archive.ArchivedFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Slf4j
public record RetrieveOperation(MetadataDto metadata) implements ArchiveOperation<Optional<ArchivedFile>> {

	private static InputStream openEntry(Path archive, String entryName) throws IOException {

		ZipFile zip = new ZipFile(archive.toFile());
		try {
			var entries = zip.entries();
			if (!entries.hasMoreElements()) throw new IOException("Archive entry is missing: " + entryName);
			ZipEntry entry = entries.nextElement();
			if (!entry.getName()
			          .equals(entryName)) throw new IOException("Archive entry has changed: " + entryName);
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

	@Override
	public Optional<ArchivedFile> execute(ArchiveContext context) throws IOException {

		Path archive = context.archivePath(metadata.fileLocation());
		if (!Files.isRegularFile(archive)) {
			log.warn("Archive missing for file {}", metadata.id());
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

}
