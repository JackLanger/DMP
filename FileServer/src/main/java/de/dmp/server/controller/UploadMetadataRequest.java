package de.dmp.server.controller;


import java.util.Set;
import java.util.UUID;


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
    Set<String> tags
) {
}