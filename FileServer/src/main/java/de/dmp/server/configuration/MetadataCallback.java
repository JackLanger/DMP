package de.dmp.server.configuration;


import com.github.f4b6a3.ulid.UlidCreator;
import de.dmp.server.data.Metadata;
import org.jspecify.annotations.NonNull;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertCallback;
import org.springframework.stereotype.Component;

import java.time.Instant;


@Component
public class MetadataCallback implements BeforeConvertCallback<Metadata> {

  @Override
  public @NonNull Metadata onBeforeConvert(
		  Metadata metadata,
		  @NonNull String collection
                                          ) {
    if (metadata.getId() == null) {
      metadata.setId(UlidCreator.getUlid()
                                .toUuid());
    }

    if (metadata.getCreatedAt() == null) {
      metadata.setCreatedAt(Instant.now());
    }

    return metadata;
  }

}