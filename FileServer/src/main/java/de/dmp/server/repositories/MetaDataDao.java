package de.dmp.server.repositories;


import de.dmp.server.data.Metadata;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.UUID;


public interface MetaDataDao extends MongoRepository<Metadata, UUID>, JpaSpecificationExecutor<Metadata> {

}
