package de.dmp.server.controller;

import java.util.Set;

public record ResumableUploadRequest(
		String filename,
		long size,
		String sha256,
		String description,
		Set<String> tags
) {
}
