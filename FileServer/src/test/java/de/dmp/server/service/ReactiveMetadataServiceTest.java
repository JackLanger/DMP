package de.dmp.server.service;

import de.dmp.server.data.Metadata;
import de.dmp.server.data.Tag;
import de.dmp.server.data.dto.MetadataDto;
import de.dmp.server.service.mapper.MetadataMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.convert.QueryMapper;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.query.Query;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReactiveMetadataServiceTest {

	@Test
	void readsAndMapsMetadataReactively() {
		ReactiveMongoTemplate mongo = mock(ReactiveMongoTemplate.class);
		var service = new ReactiveMetadataService(mongo, new MetadataMapper());
		Metadata metadata = new Metadata();
		metadata.setId(UUID.randomUUID());
		metadata.setName("report.txt");
		Tag tag = new Tag();
		tag.setName("research");
		metadata.getTags().add(tag);
		when(mongo.findAll(Metadata.class)).thenReturn(Flux.just(metadata));
		when(mongo.findById(metadata.getId(), Metadata.class)).thenReturn(Mono.just(metadata));
		when(mongo.find(any(Query.class), eq(Metadata.class))).thenReturn(Flux.just(metadata));

		assertEquals(Set.of("research"), service.findAll().blockFirst(Duration.ofSeconds(5)).tags());
		assertEquals("report.txt", service.findDtoById(metadata.getId()).block(Duration.ofSeconds(5)).name());
		assertNotNull(service.findByTagName("research").blockFirst(Duration.ofSeconds(5)));
		assertNotNull(service.findByName("report.*").blockFirst(Duration.ofSeconds(5)));

		var queries = org.mockito.ArgumentCaptor.forClass(Query.class);
		verify(mongo, times(2)).find(queries.capture(), eq(Metadata.class));
		assertEquals("research", queries.getAllValues().get(0).getQueryObject().get("tags.name"));
		Pattern pattern = assertInstanceOf(Pattern.class, queries.getAllValues().get(1).getQueryObject().get("name"));
		assertEquals("report.*", pattern.pattern());
		assertEquals(Pattern.CASE_INSENSITIVE, pattern.flags());
	}

	@Test
	void tagQueryUsesThePersistedTagIdField() throws Exception {
		ReactiveMongoTemplate mongo = mock(ReactiveMongoTemplate.class);
		var service = new ReactiveMetadataService(mongo, new MetadataMapper());
		when(mongo.find(any(Query.class), eq(Metadata.class))).thenReturn(Flux.empty());
		service.findByTagName("research").collectList().block(Duration.ofSeconds(5));

		var query = org.mockito.ArgumentCaptor.forClass(Query.class);
		verify(mongo).find(query.capture(), eq(Metadata.class));
		var context = new MongoMappingContext();
		context.setSimpleTypeHolder(MongoCustomConversions.create(adapter -> {}).getSimpleTypeHolder());
		context.afterPropertiesSet();
		var converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
		converter.afterPropertiesSet();
		var mapped = new QueryMapper(converter).getMappedObject(query.getValue().getQueryObject(),
				context.getRequiredPersistentEntity(Metadata.class));
		assertEquals("research", mapped.get("tags._id"));
	}

	@Test
	void insertsNewMetadataWithoutLookingUpTheNewId() {
		ReactiveMongoTemplate mongo = mock(ReactiveMongoTemplate.class);
		when(mongo.save(any(Tag.class))).thenAnswer(call -> Mono.just(call.getArgument(0)));
		when(mongo.insert(any(Metadata.class))).thenAnswer(call -> Mono.just(call.getArgument(0)));
		var service = new ReactiveMetadataService(mongo, new MetadataMapper());
		var dto = new MetadataDto(UUID.randomUUID(), "file.txt", "description", "key", Instant.now(), Set.of("tag"));

		Metadata result = service.create(dto).block(Duration.ofSeconds(5));

		assertNotNull(result);
		assertEquals(dto.id(), result.getId());
		assertEquals(dto.createdAt(), result.getCreatedAt());
		assertEquals("tag", result.getTags().iterator().next().getName());
		var order = inOrder(mongo);
		order.verify(mongo).save(any(Tag.class));
		order.verify(mongo).insert(any(Metadata.class));
		verify(mongo, never()).findById(any(), eq(Metadata.class));
	}

	@Test
	void updatesExistingMetadataWithoutChangingIdentityOrCreationDate() {
		ReactiveMongoTemplate mongo = mock(ReactiveMongoTemplate.class);
		when(mongo.save(any(Tag.class))).thenAnswer(call -> Mono.just(call.getArgument(0)));
		when(mongo.save(any(Metadata.class))).thenAnswer(call -> Mono.just(call.getArgument(0)));
		var service = new ReactiveMetadataService(mongo, new MetadataMapper());
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
		var order = inOrder(mongo);
		order.verify(mongo).save(any(Tag.class));
		order.verify(mongo).save(any(Metadata.class));
	}
}
