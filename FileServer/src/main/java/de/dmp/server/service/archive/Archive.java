package de.dmp.server.service.archive;

import de.dmp.server.data.dto.MetadataDto;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;

import java.nio.file.Path;

public interface Archive extends AutoCloseable {

	Path getUploadDir();

	/**
	 * Starts storing on subscription and emits the storage key after cleanup. Once started,
	 * storage continues even if subscribers cancel; subscriptions share the same result.
	 */
	Mono<String> store(FilePart file);

	/**
	 * Stores a completed transfer under its stable key. Repeating the operation replaces
	 * the same archive atomically instead of creating a duplicate document blob.
	 */
	Mono<String> store(Path source, String filename, String storageKey);

	/**
	 * Deletes the archive for the given storage key on subscription.
	 */
	Mono<Void> delete(String storageKey);

	/**
	 * Emits no value for a missing or empty archive; I/O failures are reported as errors.
	 * The returned resource opens its stream on demand, and the caller must close that stream.
	 */
	Mono<ArchivedFile> retrieve(MetadataDto metadataDto);

}
