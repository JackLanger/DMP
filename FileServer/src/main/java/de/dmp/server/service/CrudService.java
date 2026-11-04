package de.dmp.server.service;


import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Optional;


public interface CrudService<TDto, TEntity, TId> {

  List<TDto> findAll();

  List<TDto> findAllById(List<TId> ids);

  List<TDto> where(Specification<TEntity> specification);

  Optional<TDto> findById(TId id);

  TDto create(TDto entity);

  TDto update(TId id, TDto entity);

  void deleteById(TId id);


}
