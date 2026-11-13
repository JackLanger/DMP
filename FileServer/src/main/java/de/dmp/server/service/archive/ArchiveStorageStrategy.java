package de.dmp.server.service.archive;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Defines an on-disk representation and how its original content is recovered.
 * Extensions must be unique among the strategies registered in an archive context.
 * The first strategy supporting the input is selected. The source may be a file
 * or directory; a strategy encodes it into a single stored artifact.
 */
public interface ArchiveStorageStrategy {

	String extension();

	boolean supports(Path source, String filename);

	/** Writes the artifact to the supplied stream; the caller closes it afterward. */
	void write(Path source, String entryName, OutputStream output) throws IOException;

	boolean matches(Path archive, Path source, String entryName) throws IOException;

	Optional<ArchivedFile> read(Path archive) throws IOException;
}
