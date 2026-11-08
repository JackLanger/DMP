package de.dmp.server.data;


import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;


@Getter
@Setter
@Document(collection = "files")
@CompoundIndexes(
    {
        @CompoundIndex(
            name = "tags_created_idx",
            def = "{'tags': 1, 'createdAt': -1}"
        )
    }
)
public class Metadata {

  private final Set<Tag> tags = new LinkedHashSet<>();

  @Id
  private UUID id;

  private String name;

  private String description;

  private String fileLocation;

  private Instant createdAt;
  
}

