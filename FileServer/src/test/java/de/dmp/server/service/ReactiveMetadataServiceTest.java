package de.dmp.server.service;

import de.dmp.server.data.Metadata;
import de.dmp.server.data.Tag;
import de.dmp.server.data.dto.MetadataDto;
import de.dmp.server.repositories.SqliteMetadataRepository;
import de.dmp.server.service.mapper.MetadataMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReactiveMetadataServiceTest {
	private static final Duration TIMEOUT = Duration.ofSeconds(10);
	@TempDir Path temp;
	private SqliteMetadataRepository repository;
	private ReactiveMetadataService service;

	@BeforeEach
	void setUp() throws Exception {
		repository = new SqliteMetadataRepository(temp.resolve("metadata.db").toString());
		service = new ReactiveMetadataService(repository, new MetadataMapper());
	}

	@Test
	void persistsAndFindsMetadataAndTagsAcrossReopen() throws Exception {
		var id = UUID.randomUUID();
		var created = Instant.parse("2026-01-01T10:00:00Z");
		var dto = new MetadataDto(id, "Report.txt", "description", "archive-key", created, Set.of("research", "work"));
		assertEquals(id, service.create(dto).block(TIMEOUT).getId());
		var reopened = new ReactiveMetadataService(new SqliteMetadataRepository(temp.resolve("metadata.db").toString()), new MetadataMapper());
		assertEquals(dto, reopened.findDtoById(id).block(TIMEOUT));
		assertEquals(Set.of("research", "work"), reopened.findByTagName("research").blockFirst(TIMEOUT).tags());
		assertEquals(id, reopened.findByName("report.*").blockFirst(TIMEOUT).id());
		assertNull(reopened.findByTagName("other").blockFirst(TIMEOUT));
		assertNull(reopened.findById(UUID.randomUUID()).block(TIMEOUT));
		assertEquals(1, reopened.findAll().collectList().block(TIMEOUT).size());
	}

	@Test
	void updatesTagsWithoutChangingIdentityOrCreationDate() {
		var dto = new MetadataDto("file.txt", "description", "old-key", Set.of("old"));
		Metadata existing = service.create(dto).block(TIMEOUT);
		var replacement = new MetadataDto("replacement.txt", "updated", "new-key", Set.of("new"));
		service.update(existing, replacement).block(TIMEOUT);
		var result = service.findDtoById(dto.id()).block(TIMEOUT);
		assertEquals(dto.id(), result.id());
		assertEquals(dto.createdAt(), result.createdAt());
		assertEquals("new-key", result.fileLocation());
		assertEquals(Set.of("new"), result.tags());
		assertNull(service.findByTagName("old").blockFirst(TIMEOUT));
		assertEquals(result, service.findByTagName("new").blockFirst(TIMEOUT));
		service.update(existing, new MetadataDto("replacement.txt", "updated", "new-key", null)).block(TIMEOUT);
		assertTrue(service.findDtoById(dto.id()).block(TIMEOUT).tags().isEmpty());
	}

	@Test
	void duplicateIdAndInvalidTagsRollBackWrites() throws Exception {
		var dto = new MetadataDto("original", "description", "key", Set.of("tag"));
		Metadata existing = service.create(dto).block(TIMEOUT);
		assertThrows(Exception.class, () -> service.create(dto).block(TIMEOUT));
		existing.setName("changed");
		Tag invalid = new Tag();
		existing.getTags().add(invalid);
		assertThrows(Exception.class, () -> repository.update(existing));
		assertEquals(dto, service.findDtoById(dto.id()).block(TIMEOUT));
	}

	@Test
	void generatesMissingIdentityAndDateAndRejectsFutureSchema() throws Exception {
		Metadata created = service.create(new MetadataDto(null, "name", "description", "key", null, Set.of())).block(TIMEOUT);
		assertNotNull(created.getId());
		assertNotNull(created.getCreatedAt());
		try (var connection = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("metadata.db"));
		     var statement = connection.createStatement()) {
			statement.execute("PRAGMA user_version = 2");
		}
		assertThrows(java.sql.SQLException.class, () -> new SqliteMetadataRepository(temp.resolve("metadata.db").toString()));
	}
}
