package de.dmp.server.service.archive.ops;

import de.dmp.server.data.dto.MetadataDto;
import de.dmp.server.service.archive.ArchiveContext;
import de.dmp.server.service.archive.ArchivedFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Slf4j
public record RetrieveOperation(MetadataDto metadata) implements ArchiveOperation<ArchivedFile> {

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
	public Mono<ArchivedFile> execute(ArchiveContext context, Scheduler scheduler) {
		return Mono.fromCallable(() -> retrieve(context))
				.subscribeOn(scheduler)
				.flatMap(Mono::justOrEmpty);
	}

	private Optional<ArchivedFile> retrieve(ArchiveContext context) throws IOException {

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
					new InputStreamResource(() -> openEntry(archive, entryName)), entry.getSize(),
					checksum(archive, entryName)
			));
		}
	}

	private String checksum(Path archive, String entryName) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream input = openEntry(archive, entryName)) {
				byte[] buffer = new byte[8192];
				for (int count; (count = input.read(buffer)) != -1; ) digest.update(buffer, 0, count);
			}
			return java.util.HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

}
