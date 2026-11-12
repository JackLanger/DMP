package de.dmp.server.service;

import de.dmp.server.data.Metadata;
import de.dmp.server.data.dto.MetadataDto;
import de.dmp.server.service.mapper.MetadataMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReactiveUploadMetadataServiceTest {

	@Test
	void insertsNewMetadataWithoutLookingUpTheNewId() {
		ReactiveMongoTemplate mongo = mock(ReactiveMongoTemplate.class);
		when(mongo.insert(any(Metadata.class))).thenAnswer(call -> Mono.just(call.getArgument(0)));
		var service = new ReactiveUploadMetadataService(mongo, new MetadataMapper());
		var dto = new MetadataDto(UUID.randomUUID(), "file.txt", "description", "key", Instant.now(), Set.of("tag"));

		Metadata result = service.create(dto).block(Duration.ofSeconds(5));

		assertNotNull(result);
		assertEquals(dto.id(), result.getId());
		assertEquals(dto.createdAt(), result.getCreatedAt());
		assertEquals("tag", result.getTags().iterator().next().getName());
		verify(mongo).insert(any(Metadata.class));
		verify(mongo, never()).findById(any(), eq(Metadata.class));
	}

	@Test
	void updatesExistingMetadataWithoutChangingIdentityOrCreationDate() {
		ReactiveMongoTemplate mongo = mock(ReactiveMongoTemplate.class);
		when(mongo.save(any(Metadata.class))).thenAnswer(call -> Mono.just(call.getArgument(0)));
		var service = new ReactiveUploadMetadataService(mongo, new MetadataMapper());
		Metadata existing = new Metadata();
		UUID id = UUID.randomUUID();
		Instant created = Instant.now();
		existing.setId(id);
		existing.setCreatedAt(created);
		var replacement = new MetadataDto("replacement.txt", "updated", "new-key", Set.of("new"));

		Metadata result = service.update(existing, replacement).block(Duration.ofSeconds(5));

		assertNotNull(result);
		assertEquals(id, result.getId());
		assertEquals(created, result.getCreatedAt());
		assertEquals("replacement.txt", result.getName());
		assertEquals("new-key", result.getFileLocation());
		assertEquals("new", result.getTags().iterator().next().getName());
	}
}
