package de.dmp.server.controller;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.Set;

public record ResumableUploadRequest(
		String filename,
		long size,
		String sha256,
		String description,
		@JsonAlias("filetype") String fileType,
		Set<String> tags
) {
}
