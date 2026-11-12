package de.dmp.server.service.archive.ops;

import de.dmp.server.service.archive.ArchiveContext;

import java.io.IOException;
import java.nio.file.Files;

public record DeleteOperation(String storageKey) implements ArchiveOperation<Void> {

	@Override
	public Void execute(ArchiveContext context) throws IOException {

		Files.deleteIfExists(context.archivePath(storageKey));
		return null;
	}

}
