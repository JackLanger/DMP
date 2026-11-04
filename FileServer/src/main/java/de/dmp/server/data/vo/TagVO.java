package de.dmp.server.data.vo;


public record TagVO(String name) {

  public TagVO {

    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Tag name cannot be null or blank");
    }
  }

}
