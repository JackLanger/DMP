package de.dmp.server.data.dto;


import com.github.f4b6a3.ulid.UlidCreator;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;


public record MetadataDto(
    UUID id, String name, String description, String fileLocation, Instant createdAt,
    Set<String> tags
) {

  public MetadataDto(String name, String description, String fileLocation, Set<String> tags) {

    this(
        UlidCreator.getUlid()
                   .toUuid(), name, description, fileLocation, Instant.now(), tags
    );
  }

}
