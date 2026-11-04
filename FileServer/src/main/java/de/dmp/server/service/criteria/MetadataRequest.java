package de.dmp.server.service.criteria;


import java.util.Set;


public record MetadataRequest(
    String name,
    String description,
    String fileLocation,
    Set<String> tagNames
) {
}