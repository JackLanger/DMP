package de.dmp.server.service;

import de.dmp.server.data.Metadata;
import de.dmp.server.data.Tag;
import de.dmp.server.data.dto.MetadataDto;
import de.dmp.server.data.vo.MetadataVO;
import de.dmp.server.service.mapper.MetadataMapper;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
public class ReactiveMetadataService {

	private final ReactiveMongoTemplate mongo;
	private final MetadataMapper mapper;

	public ReactiveMetadataService(ReactiveMongoTemplate mongo, MetadataMapper mapper) {
		this.mongo = mongo;
		this.mapper = mapper;
	}

	public Mono<Metadata> findById(UUID id) {
		return mongo.findById(id, Metadata.class);
	}

	public Flux<MetadataDto> findAll() {
		return mongo.findAll(Metadata.class).map(mapper::toDto);
	}

	public Flux<MetadataDto> findByTagName(String name) {
		return mongo.find(Query.query(Criteria.where("tags.name").is(name)), Metadata.class).map(mapper::toDto);
	}

	public Flux<MetadataDto> findByName(String name) {
		return mongo.find(Query.query(Criteria.where("name").regex(name, "i")), Metadata.class).map(mapper::toDto);
	}

	public Mono<MetadataDto> findDtoById(UUID id) {
		return findById(id).map(mapper::toDto);
	}

	public Mono<Metadata> create(MetadataDto dto) {
		return Mono.defer(() -> {
			Metadata entity = mapper.apply(new MetadataVO(dto));
			entity.setCreatedAt(dto.createdAt());
			return saveTags(entity).flatMap(mongo::insert);
		});
	}

	public Mono<Metadata> update(Metadata existing, MetadataDto dto) {
		return Mono.defer(() -> {
			MetadataVO validated = new MetadataVO(dto);
			if (dto.tags() == null) {
				existing.getTags().clear();
			}
			return saveTags(mapper.apply(validated, existing)).flatMap(mongo::save);
		});
	}

	private Mono<Metadata> saveTags(Metadata metadata) {
		return Flux.fromIterable(metadata.getTags())
				.concatMap(tag -> mongo.save(tag))
				.collectList()
				.map(savedTags -> {
					metadata.getTags().clear();
					metadata.getTags().addAll(savedTags);
					return metadata;
				});
	}
}
