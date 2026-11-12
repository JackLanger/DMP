package de.dmp.server.service.archive.ops;

import de.dmp.server.service.archive.ArchiveContext;

import java.io.IOException;

@FunctionalInterface
public interface ArchiveOperation<T> {

	T execute(ArchiveContext context) throws IOException;

}
