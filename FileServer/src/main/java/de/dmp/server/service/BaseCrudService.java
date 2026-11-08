package de.dmp.server.service;


import de.dmp.server.data.Metadata;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;


abstract class BaseCrudService<TDto, TEntity, TId> implements CrudService<TDto, TEntity, TId> {

	protected final MongoRepository<TEntity, TId> dao;


	protected BaseCrudService(MongoRepository<TEntity, TId> repository) {

		this.dao = repository;
	}


	@Override
	public List<TDto> findAll() {

		return dao.findAll()
		          .stream()
		          .map(this::mapToDto)
		          .toList();
	}


	@Override
	public List<TDto> findAllById(List<TId> ids) {

		return dao.findAllById(ids)
		          .stream()
		          .map(this::mapToDto)
		          .toList();
	}


	@Override
	public Optional<TDto> findById(TId id) {

		return dao.findById(id)
		          .map(this::mapToDto);
	}


	@Override
	public TDto create(TDto entity) {

		return mapToDto(dao.save(mapToEntity(entity)));
	}


	@Override
	public TDto update(TId id, TDto dto) {


		TEntity entity = dao.findById(id)
		                    .orElseThrow(() -> new DataNotFound(id, Metadata.class));

		updateEntity(dto, entity);
		return mapToDto(dao.save(entity));
	}


	@Override
	public void deleteById(TId id) {

		dao.deleteById(id);
	}


	protected abstract void updateEntity(TDto dto, TEntity entity);

	protected abstract TDto mapToDto(TEntity entity);

	protected abstract TEntity mapToEntity(TDto dto);

}

