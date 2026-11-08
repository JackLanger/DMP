package de.dmp.server.service;


import de.dmp.server.service.criteria.MongoSpecification;

import java.util.List;
import java.util.Optional;


public interface CrudService<TDto, TEntity, TId> {

	List<TDto> findAll();

	List<TDto> findAllById(List<TId> ids);

	List<TDto> where(MongoSpecification<TEntity> criteria);

	Optional<TDto> findById(TId id);

	TDto create(TDto entity);

	TDto update(TId id, TDto entity);

	void deleteById(TId id);


}
