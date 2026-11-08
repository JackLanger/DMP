package de.dmp.server.service.criteria;

import org.springframework.data.mongodb.core.query.Criteria;

@FunctionalInterface
public interface MongoSpecification<T> {

	Criteria toCriteria();

}