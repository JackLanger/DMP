package de.dmp.server.repositories;


import de.dmp.server.data.Tag;
import org.springframework.data.mongodb.repository.MongoRepository;


public interface TagDao extends MongoRepository<Tag, String> {
}