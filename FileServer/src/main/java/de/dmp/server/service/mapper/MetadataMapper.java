package de.dmp.server.service.mapper;


import de.dmp.server.data.Metadata;
import de.dmp.server.data.Tag;
import de.dmp.server.data.dto.MetadataDto;
import de.dmp.server.data.vo.MetadataVO;
import org.springframework.stereotype.Component;


/**
 * Mapper responsible for mapping from {@link MetadataVO} to {@link Metadata}
 */
@Component
public class MetadataMapper implements java.util.function.Function<MetadataVO, Metadata> {

  public MetadataDto toDto(Metadata metadata) {
    return new MetadataDto(metadata.getId(), metadata.getName(), metadata.getDescription(),
        metadata.getFileLocation(), metadata.getCreatedAt(),
        metadata.getTags().stream().map(Tag::getName).collect(java.util.stream.Collectors.toSet()));
  }

  @Override
  public Metadata apply(MetadataVO vo) {

    Metadata entity = new Metadata();
    entity.setId(vo.id());
    entity.setName(vo.name());
    entity.setDescription(vo.description());
    entity.setFileLocation(vo.fileLocation());
    if (vo.tags() != null) {
      for (var tagName : vo.tags()) {
        var tag = new Tag();
        tag.setName(tagName);
        entity.getTags()
              .add(tag);
      }
    }
    return entity;
  }


  public Metadata apply(MetadataVO vo, Metadata entity) {

    entity.setName(vo.name());
    entity.setDescription(vo.description());
    entity.setFileLocation(vo.fileLocation());
    if (vo.tags() != null) {
      entity.getTags()
            .clear();
      for (var tagName : vo.tags()) {
        var tag = new Tag();
        tag.setName(tagName);
        entity.getTags()
              .add(tag);
      }
    }
    return entity;
  }

}
