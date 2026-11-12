package de.dmp.server.service.archive.ops;

import com.github.f4b6a3.ulid.UlidCreator;
import de.dmp.server.service.archive.ArchiveContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.multipart.FilePart;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
public record StoreOperation(FilePart file) implements ArchiveOperation<String> {

	@Override
	public String execute(ArchiveContext context) throws IOException {

		Path destination = Files.createTempFile(context.uploadDir(), "tmp", ".upload");
		try {
			file.transferTo(destination)
			    .block();
			return archive(context, destination);
		} finally {
			cleanupTemporary(destination);
		}
	}

	public static String archive(ArchiveContext context, Path destination) throws IOException {

		String storageKey = UlidCreator.getUlid()
		                               .toString();
		Path archive = context.archivePath(storageKey);
		var output = Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW);
		try (output; var zip = new ZipOutputStream(output)) {
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
	}

	public static void cleanupTemporary(Path destination) {

		try {
			Files.deleteIfExists(destination);
		} catch (IOException ex) {
			log.warn("Failed to clean temporary upload {}", destination, ex);
		}
	}

}
