package de.dmp.server.service;


import de.dmp.server.data.Metadata;
import de.dmp.server.data.Tag;
import de.dmp.server.data.dto.MetadataDto;
import de.dmp.server.data.vo.MetadataVO;
import de.dmp.server.repositories.MetaDataDao;
import de.dmp.server.repositories.TagDao;
import de.dmp.server.service.criteria.MongoSpecification;
import de.dmp.server.service.mapper.MetadataMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


@Service
public class MetadataService extends BaseCrudService<MetadataDto, Metadata, java.util.UUID> {

	private final MetadataMapper metadataMapper;
	private final TagDao tagDao;
	private final MongoTemplate mongoTemplate;


	@Autowired
	public MetadataService(
			MetaDataDao metaDataDao,
			TagDao tagDao,
			MetadataMapper metadataMapper,
			MongoTemplate mongoTemplate
	                      ) {

		super(metaDataDao);
		this.metadataMapper = metadataMapper;
		this.tagDao = tagDao;
		this.mongoTemplate = mongoTemplate;
	}

	public List<MetadataDto> findByTagName(String tagName) {

		return ((MetaDataDao) dao).findByTagsName(tagName)
		                          .stream()
		                          .map(this::mapToDto)
		                          .toList();
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

		entity.getTags()
		      .clear();

		if (dto.tags() != null) {
			final List<Tag> existing = tagDao.findAllById(dto.tags());

			entity.getTags()
			      .addAll(existing);

			final Set<String> existingTags = existing.stream()
			                                         .map(Tag::getName)
			                                         .collect(Collectors.toSet());

			dto.tags()
			   .stream()
			   .filter(name -> !existingTags.contains(name))
			   .map(name -> {
				   Tag tag = new Tag();
				   tag.setName(name);
				   return tag;
			   })
			   .forEach(entity.getTags()::add);
		}
	}

	@Override
	protected Metadata mapToEntity(MetadataDto dto) {

		MetadataVO validated = new MetadataVO(dto);

		Metadata entity = dao.findById(dto.id())
		                     .orElseThrow(() -> new DataNotFound(dto.id(), Metadata.class));

		return metadataMapper.apply(validated, entity);
	}


	@Override public List<MetadataDto> where(MongoSpecification<Metadata> specification) {

		return mongoTemplate.find(new Query(specification.toCriteria()), Metadata.class)
		                    .stream()
		                    .map(this::mapToDto)
		                    .toList();
	}

}
