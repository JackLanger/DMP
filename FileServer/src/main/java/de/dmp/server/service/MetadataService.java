package de.dmp.server.service;


import de.dmp.server.data.Metadata;
import de.dmp.server.data.Tag;
import de.dmp.server.data.dto.MetadataDto;
import de.dmp.server.data.vo.MetadataVO;
import de.dmp.server.data.vo.TagVO;
import de.dmp.server.repositories.MetaDataDao;
import de.dmp.server.repositories.TagDao;
import de.dmp.server.service.mapper.MetadataMapper;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;


@Service
public class MetadataService extends BaseCrudService<MetadataDto, Metadata, java.util.UUID> {

  private final MetadataMapper metadataMapper;

  private final TagDao tagDao;


  @Autowired
  public MetadataService(MetaDataDao metaDataDao, TagDao tagDao, MetadataMapper metadataMapper) {

    super(metaDataDao);
    this.metadataMapper = metadataMapper;
    this.tagDao = tagDao;
  }


  @Override
  public List<MetadataDto> where(Specification<Metadata> specification) {

    return ((MetaDataDao) dao).findAll(specification)
                              .stream()
                              .map(this::mapToDto)
                              .collect(Collectors.toList());
  }


  public List<MetadataDto> findByTagName(String tagName) {

    return ((MetaDataDao) dao).findAll((root, query, criteriaBuilder) ->
                                           criteriaBuilder.isMember(
                                               tagName,
                                               root.get("tags")
                                                   .get("name")
                                           )
                              )
                              .stream()
                              .map(this::mapToDto)
                              .collect(Collectors.toList());
  }


  @Override
  protected MetadataDto mapToDto(Metadata metadata) {

    return new MetadataDto(
        metadata.getId(),
        metadata.getName(),
        metadata.getDescription(),
        metadata.getFileLocation(),
        metadata.getCreatedAt(),
        metadata.getTags()
                .stream()
                .map(Tag::getName)
                .collect(Collectors.toSet())
    );
  }


  @Override
  protected void updateEntity(MetadataDto dto, Metadata entity) {

    entity.setName(dto.name());
    entity.setDescription(dto.description());
    entity.setFileLocation(dto.fileLocation());

    // Update tags
    entity.getTags()
          .clear();
    if (dto.tags() != null) {


      final List<Tag> existing = tagDao.findAllById(dto.tags());
      entity.getTags()
            .addAll(existing);

      final List<String> existingTags = existing.stream()
                                                .map(Tag::getName)
                                                .toList();

      Predicate<String> inExisting = existingTags::contains;

      for (String name : dto.tags()
                            .stream()
                            .filter(inExisting.negate())
                            .toList()) {

        var vo = new TagVO(name);
        Tag tag = new Tag();
        tag.setName(vo.name());
        entity.getTags()
              .add(tag);
      }
    }
  }


  @Override
  protected Metadata mapToEntity(MetadataDto dto) {

    MetadataVO validated = new MetadataVO(dto); // validates input
    
    Metadata entity = dao.findById(dto.id())
                         .orElseThrow(() -> new EntityNotFoundException("Metadata not found: " + dto.id()));

    return metadataMapper.apply(validated, entity); // updates the managed entity
  }


}
