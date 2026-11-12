package de.dmp.server.service;

import de.dmp.server.data.dto.MetadataDto;
import org.springframework.http.codec.multipart.FilePart;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Future;

public interface Archive extends AutoCloseable {

	Path getUploadDir();

	/**
	 * Stores the file and returns its storage key. This method blocks until the transfer and archive are complete.
	 */
	String store(FilePart file) throws IOException;

	/**
	 * Submits a store operation; the future returns the storage key or reports the storage failure.
	 */
	Future<String> storeAsync(FilePart file);

	void delete(String storageKey) throws IOException;

	/** Returns an empty result for a missing or empty archive; I/O failures are reported by the future. */
	Future<Optional<ArchivedFile>> retrieveAsync(MetadataDto metadataDto);

	/** The returned resource opens its stream on demand, and the caller must close that stream. */
	Optional<ArchivedFile> retrieve(MetadataDto metadataDto) throws IOException;

}
