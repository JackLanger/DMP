package de.dmp.server.service.archive;

import org.springframework.core.io.Resource;

/**
 * An archived file's content and uncompressed size, or a negative size when unknown.
 * The resource opens the content on demand; its stream must be closed by the caller.
 */
public record ArchivedFile(Resource content, long size, String sha256) {

	public ArchivedFile(Resource content, long size) {
		this(content, size, null);
	}

}
