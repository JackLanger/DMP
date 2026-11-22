package de.dmp.server.repositories;

import com.github.f4b6a3.ulid.UlidCreator;
import de.dmp.server.data.Metadata;
import de.dmp.server.data.FileType;
import de.dmp.server.data.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Repository
public class SqliteMetadataRepository {
	private final String url;

	public SqliteMetadataRepository(@Value("${metadata.database:data/metadata.db}") String database) throws IOException, SQLException {
		Path path = Path.of(database).toAbsolutePath().normalize();
		Files.createDirectories(path.getParent());
		url = "jdbc:sqlite:" + path;
		try (Connection connection = open(); Statement statement = connection.createStatement()) {
			int schemaVersion;
			try (ResultSet version = statement.executeQuery("PRAGMA user_version")) {
				schemaVersion = version.next() ? version.getInt(1) : 0;
				if (schemaVersion > 2) {
					throw new SQLException("Unsupported metadata database schema version");
				}
			}
			connection.setAutoCommit(false);
			try {
				statement.execute("CREATE TABLE IF NOT EXISTS metadata (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, "
						+ "description TEXT NOT NULL, file_location TEXT, created_at TEXT NOT NULL)");
				statement.execute("CREATE TABLE IF NOT EXISTS metadata_tags (metadata_id TEXT NOT NULL, name TEXT NOT NULL, "
						+ "PRIMARY KEY (metadata_id, name), FOREIGN KEY (metadata_id) REFERENCES metadata(id) ON DELETE CASCADE)");
				statement.execute("CREATE INDEX IF NOT EXISTS metadata_tags_name_idx ON metadata_tags(name)");
				if (schemaVersion < 2) {
					statement.execute("ALTER TABLE metadata ADD COLUMN file_type TEXT NOT NULL DEFAULT 'FILE'");
				}
				statement.execute("PRAGMA user_version = 2");
				connection.commit();
			} catch (SQLException ex) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					ex.addSuppressed(rollbackFailure);
				}
				throw ex;
			}
		}
	}

	private Connection open() throws SQLException {
		Connection connection = DriverManager.getConnection(url);
		try (Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA foreign_keys = ON");
			statement.execute("PRAGMA busy_timeout = 5000");
			return connection;
		} catch (SQLException ex) {
			connection.close();
			throw ex;
		}
	}

	public Metadata findById(UUID id) throws SQLException {
		List<Metadata> result = query("WHERE m.id = ?", id.toString());
		return result.isEmpty() ? null : result.getFirst();
	}

	public List<Metadata> findAll() throws SQLException {
		return query("", null);
	}

	public List<Metadata> findByTagName(String name) throws SQLException {
		return query("WHERE EXISTS (SELECT 1 FROM metadata_tags filter WHERE filter.metadata_id = m.id AND filter.name = ?)", name);
	}

	public List<Metadata> findByName(String regex) throws SQLException {
		Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
		return findAll().stream().filter(metadata -> pattern.matcher(metadata.getName()).find()).toList();
	}

	private List<Metadata> query(String where, String parameter) throws SQLException {
		String sql = "SELECT m.id, m.name, m.description, m.file_location, m.created_at, m.file_type, t.name AS tag_name "
				+ "FROM metadata m LEFT JOIN metadata_tags t ON t.metadata_id = m.id " + where + " ORDER BY m.rowid, t.rowid";
		Map<UUID, Metadata> results = new LinkedHashMap<>();
		try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
			if (parameter != null) statement.setString(1, parameter);
			try (ResultSet rows = statement.executeQuery()) {
				while (rows.next()) {
					UUID id = UUID.fromString(rows.getString("id"));
					Metadata metadata = results.get(id);
					if (metadata == null) {
						metadata = new Metadata();
						metadata.setId(id);
						metadata.setName(rows.getString("name"));
						metadata.setDescription(rows.getString("description"));
						metadata.setFileLocation(rows.getString("file_location"));
						metadata.setCreatedAt(Instant.parse(rows.getString("created_at")));
						metadata.setType(FileType.parse(rows.getString("file_type")));
						results.put(id, metadata);
					}
					String name = rows.getString("tag_name");
					if (name != null) {
						Tag tag = new Tag();
						tag.setName(name);
						metadata.getTags().add(tag);
					}
				}
			}
		}
		return new ArrayList<>(results.values());
	}

	public Metadata insert(Metadata metadata) throws SQLException {
		if (metadata.getId() == null) metadata.setId(UlidCreator.getUlid().toUuid());
		if (metadata.getCreatedAt() == null) metadata.setCreatedAt(Instant.now());
		return write(metadata, true);
	}

	public Metadata update(Metadata metadata) throws SQLException {
		return write(metadata, false);
	}

	private Metadata write(Metadata metadata, boolean insert) throws SQLException {
		try (Connection connection = open()) {
			connection.setAutoCommit(false);
			try {
				String sql = insert
						? "INSERT INTO metadata (name, description, file_location, created_at, file_type, id) VALUES (?, ?, ?, ?, ?, ?)"
						: "UPDATE metadata SET name = ?, description = ?, file_location = ?, created_at = ?, file_type = ? WHERE id = ?";
				try (PreparedStatement statement = connection.prepareStatement(sql)) {
					statement.setString(1, metadata.getName());
					statement.setString(2, metadata.getDescription());
					statement.setString(3, metadata.getFileLocation());
					statement.setString(4, metadata.getCreatedAt().toString());
					statement.setString(5, metadata.getType().name());
					statement.setString(6, metadata.getId().toString());
					if (statement.executeUpdate() != 1) throw new SQLException("Metadata not found: " + metadata.getId());
				}
				if (!insert) {
					try (PreparedStatement statement = connection.prepareStatement("DELETE FROM metadata_tags WHERE metadata_id = ?")) {
						statement.setString(1, metadata.getId().toString());
						statement.executeUpdate();
					}
				}
				try (PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO metadata_tags (metadata_id, name) VALUES (?, ?)")) {
					for (Tag tag : metadata.getTags()) {
						statement.setString(1, metadata.getId().toString());
						statement.setString(2, tag.getName());
						statement.addBatch();
					}
					statement.executeBatch();
				}
				connection.commit();
				return metadata;
			} catch (SQLException | RuntimeException ex) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					ex.addSuppressed(rollbackFailure);
				}
				throw ex;
			}
		}
	}
}
