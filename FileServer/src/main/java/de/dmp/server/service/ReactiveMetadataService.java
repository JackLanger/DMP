package de.dmp.server.service;

import de.dmp.server.data.Metadata;
import de.dmp.server.data.dto.MetadataDto;
import de.dmp.server.data.vo.MetadataVO;
import de.dmp.server.service.mapper.MetadataMapper;
import de.dmp.server.repositories.SqliteMetadataRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@Service
public class ReactiveMetadataService {

	private final SqliteMetadataRepository repository;
	private final MetadataMapper mapper;

	public ReactiveMetadataService(SqliteMetadataRepository repository, MetadataMapper mapper) {
		this.repository = repository;
		this.mapper = mapper;
	}

	public Mono<Metadata> findById(UUID id) {
		return Mono.fromCallable(() -> repository.findById(id)).subscribeOn(Schedulers.boundedElastic());
	}

	public Flux<MetadataDto> findAll() {
		return Mono.fromCallable(repository::findAll).subscribeOn(Schedulers.boundedElastic())
				.flatMapMany(Flux::fromIterable).map(mapper::toDto);
	}

	public Flux<MetadataDto> findByTagName(String name) {
		return Mono.fromCallable(() -> repository.findByTagName(name)).subscribeOn(Schedulers.boundedElastic())
				.flatMapMany(Flux::fromIterable).map(mapper::toDto);
	}

	public Flux<MetadataDto> findByName(String name) {
		return Mono.fromCallable(() -> repository.findByName(name)).subscribeOn(Schedulers.boundedElastic())
				.flatMapMany(Flux::fromIterable).map(mapper::toDto);
	}

	public Mono<MetadataDto> findDtoById(UUID id) {
		return findById(id).map(mapper::toDto);
	}

	public Mono<Metadata> create(MetadataDto dto) {
		return Mono.fromCallable(() -> {
			Metadata entity = mapper.apply(new MetadataVO(dto));
			entity.setCreatedAt(dto.createdAt());
			return repository.insert(entity);
		}).subscribeOn(Schedulers.boundedElastic());
	}

	public Mono<Metadata> update(Metadata existing, MetadataDto dto) {
		return Mono.fromCallable(() -> {
			MetadataVO validated = new MetadataVO(dto);
			if (dto.tags() == null) {
				existing.getTags().clear();
			}
			return repository.update(mapper.apply(validated, existing));
		}).subscribeOn(Schedulers.boundedElastic());
	}
}
