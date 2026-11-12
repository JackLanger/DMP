package de.dmp.server.service;

import de.dmp.server.data.Metadata;
import de.dmp.server.data.dto.MetadataDto;
import de.dmp.server.data.vo.MetadataVO;
import de.dmp.server.service.mapper.MetadataMapper;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
public class ReactiveUploadMetadataService {

	private final ReactiveMongoTemplate mongo;
	private final MetadataMapper mapper;

	public ReactiveUploadMetadataService(ReactiveMongoTemplate mongo, MetadataMapper mapper) {
		this.mongo = mongo;
		this.mapper = mapper;
	}

	public Mono<Metadata> findById(UUID id) {
		return mongo.findById(id, Metadata.class);
	}

	public Mono<Metadata> create(MetadataDto dto) {
		return Mono.defer(() -> {
			Metadata entity = mapper.apply(new MetadataVO(dto));
			entity.setCreatedAt(dto.createdAt());
			return mongo.insert(entity);
		});
	}

	public Mono<Metadata> update(Metadata existing, MetadataDto dto) {
		return Mono.defer(() -> {
			MetadataVO validated = new MetadataVO(dto);
			if (dto.tags() == null) {
				existing.getTags().clear();
			}
			return mongo.save(mapper.apply(validated, existing));
		});
	}
}
