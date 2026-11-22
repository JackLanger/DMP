package de.dmp.server.controller;


import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAlias;


/**
 * Metadata sent with an upload request
 *
 * @param uuid
 * @param description
 * @param tags
 */
public record UploadMetadataRequest(
    UUID uuid,
    String description,
	@JsonAlias("filetype") String fileType,
    Set<String> tags
) {
}