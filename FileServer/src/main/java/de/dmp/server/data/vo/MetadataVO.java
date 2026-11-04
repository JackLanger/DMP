package de.dmp.server.data.vo;


import de.dmp.server.data.dto.MetadataDto;

import java.util.Set;
import java.util.UUID;


public record MetadataVO(UUID id, String name, String description, String fileLocation, Set<String> tags) {

  public MetadataVO(MetadataDto dto) {

    this(dto.id(), dto.name(), dto.description(), dto.fileLocation(), dto.tags());
  }


  public MetadataVO {

    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Metadata name cannot be null or blank");
    }

    if (description == null || description.isBlank()) {
      throw new IllegalArgumentException("Metadata description cannot be null or blank");
    }
  }

}
