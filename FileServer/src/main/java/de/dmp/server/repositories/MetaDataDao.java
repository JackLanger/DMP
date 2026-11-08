package de.dmp.server.repositories;


import de.dmp.server.data.Metadata;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.UUID;

public interface MetaDataDao extends MongoRepository<Metadata, UUID> {

	List<Metadata> findByTagsName(String name);

}
