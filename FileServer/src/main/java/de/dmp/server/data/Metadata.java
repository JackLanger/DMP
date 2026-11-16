package de.dmp.server.data;


import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;


@Getter
@Setter
public class Metadata {

  private final Set<Tag> tags = new LinkedHashSet<>();

  private UUID id;

  private String name;

  private String description;

  private String fileLocation;

  private Instant createdAt;
  
}
