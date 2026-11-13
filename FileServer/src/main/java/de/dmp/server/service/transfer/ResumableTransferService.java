package de.dmp.server.service.transfer;

import de.dmp.server.controller.ResumableUploadRequest;
import de.dmp.server.controller.TransferStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.Channels;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Properties;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class ResumableTransferService {

	public static final int MAX_CHUNK_SIZE = 256 * 1024;
	private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
	private static final String STATE_FILE = "transfer.properties";

	private final Path transferRoot;
	private final Duration expiry;
	private final Set<UUID> finalizing = new HashSet<>();

	@Autowired
	public ResumableTransferService(
			@Value("${archive.upload:data/archive}") String uploadDir,
			@Value("${archive.transfers.expiry:PT24H}") Duration expiry) throws IOException {
		this(Path.of(uploadDir), expiry);
	}

	public ResumableTransferService(Path uploadDir, Duration expiry) throws IOException {
		if (expiry.isNegative() || expiry.isZero()) {
			throw new IllegalArgumentException("Transfer expiry must be positive");
		}
		this.transferRoot = uploadDir.toAbsolutePath().normalize().resolve(".transfers");
		this.expiry = expiry;
		Files.createDirectories(transferRoot);
	}

	public synchronized TransferStatus create(ResumableUploadRequest request) {
		if (request.filename() == null || request.filename().isBlank() || request.filename().length() > 255) {
			throw badRequest("A filename of at most 255 characters is required");
		}
		if (request.size() < 0) throw badRequest("Transfer size cannot be negative");
		if (request.sha256() == null || !SHA256.matcher(request.sha256()).matches()) {
			throw badRequest("A SHA-256 checksum is required");
		}
		if (request.tags() != null && request.tags().stream().anyMatch(Objects::isNull)) {
			throw badRequest("Tags cannot contain null values");
		}
		cleanupExpired();
		UUID id = UUID.randomUUID();
		Instant expiresAt = Instant.now().plus(expiry);
		Properties properties = new Properties();
		properties.setProperty("filename", encode(request.filename()));
		properties.setProperty("size", Long.toString(request.size()));
		properties.setProperty("sha256", request.sha256().toLowerCase());
		properties.setProperty("description", encode(request.description() == null ? "" : request.description()));
		Set<String> tags = request.tags() == null ? Set.of() : request.tags();
		properties.setProperty("tagCount", Integer.toString(tags.size()));
		int index = 0;
		for (String tag : tags) properties.setProperty("tag." + index++, encode(tag));
		properties.setProperty("offset", "0");
		properties.setProperty("expiresAt", expiresAt.toString());
		try {
			Path directory = directory(id);
			Files.createDirectory(directory);
			Files.createFile(partPath(id));
			save(id, properties);
			return status(id, properties);
		} catch (IOException ex) {
			throw new IllegalStateException("Could not create transfer session", ex);
		}
	}

	public synchronized TransferStatus status(UUID id) {
		cleanupExpired();
		Properties properties = load(id);
		reconcileCheckpoint(id, properties);
		return status(id, properties);
	}

	public synchronized TransferStatus append(UUID id, long start, long end, long total, byte[] chunk) {
		cleanupExpired();
		if (chunk == null || chunk.length == 0 || chunk.length > MAX_CHUNK_SIZE) {
			throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Chunk must contain 1 byte to 256 KiB");
		}
		Properties properties = load(id);
		reconcileCheckpoint(id, properties);
		if (properties.getProperty("completed") != null) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Transfer is already complete");
		}
		long offset = Long.parseLong(properties.getProperty("offset"));
		long size = Long.parseLong(properties.getProperty("size"));
		if (total != size || start < 0 || end < start || end - start + 1 != chunk.length || end >= total) {
			throw badRequest("Content-Range does not match the transfer length or chunk");
		}
		try (FileChannel file = FileChannel.open(partPath(id), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
			if (start < offset) {
				if (end >= offset || !matches(file, start, chunk)) {
					throw new ResponseStatusException(HttpStatus.CONFLICT, "Retry differs from the stored chunk");
				}
				return status(id, properties);
			}
			if (start != offset) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Chunk must start at the current upload offset");
			}
			file.position(start);
			ByteBuffer buffer = ByteBuffer.wrap(chunk);
			while (buffer.hasRemaining()) file.write(buffer);
			file.force(true);
			long nextOffset = end + 1;
			Instant expiresAt = Instant.now().plus(expiry);
			properties.setProperty("offset", Long.toString(nextOffset));
			properties.setProperty("expiresAt", expiresAt.toString());
			save(id, properties);
			return status(id, properties);
		} catch (IOException ex) {
			throw new IllegalStateException("Could not persist transfer chunk", ex);
		}
	}

	public synchronized Upload complete(UUID id) {
		cleanupExpired();
		Properties properties = load(id);
		reconcileCheckpoint(id, properties);
		long size = Long.parseLong(properties.getProperty("size"));
		if (Long.parseLong(properties.getProperty("offset")) != size) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Transfer is incomplete");
		}
		String completedStorageKey = properties.getProperty("completed");
		String checksum = checksum(partPath(id));
		if (!MessageDigest.isEqual(
				checksum.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
				properties.getProperty("sha256").getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "SHA-256 checksum does not match");
		}
		int tagCount = Integer.parseInt(properties.getProperty("tagCount", "0"));
		Set<String> tags = java.util.stream.IntStream.range(0, tagCount)
				.mapToObj(index -> decode(properties.getProperty("tag." + index)))
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		return new Upload(id, partPath(id), decode(properties.getProperty("filename")),
				decode(properties.getProperty("description")), tags, checksum, completedStorageKey);
	}

	public synchronized void markCompleted(UUID id, String storageKey) {
		Properties properties = load(id);
		properties.setProperty("completed", storageKey);
		try {
			save(id, properties);
		} catch (IOException ex) {
			throw new IllegalStateException("Could not persist completed transfer", ex);
		}
	}

	public synchronized void delete(UUID id) {
		if (finalizing.contains(id)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Transfer is being completed");
		}
		Path directory = directory(id);
		try {
			deleteDirectory(directory);
		} catch (IOException ex) {
			throw new IllegalStateException("Could not remove transfer session", ex);
		}

		public synchronized boolean beginFinalization(UUID id) {
			return finalizing.add(id);
		}

		public synchronized void endFinalization(UUID id) {
			finalizing.remove(id);
		}
	}

	@Scheduled(fixedDelayString = "${archive.transfers.cleanup-interval:3600000}")
	public synchronized void cleanupExpired() {
		try (DirectoryStream<Path> directories = Files.newDirectoryStream(transferRoot)) {
			Instant now = Instant.now();
			for (Path directory : directories) {
				if (!Files.isDirectory(directory)) continue;
				try {
					UUID id = UUID.fromString(directory.getFileName().toString());
					if (finalizing.contains(id)) continue;
					Properties properties = read(directory.resolve(STATE_FILE));
					if (!Instant.parse(properties.getProperty("expiresAt")).isAfter(now)) deleteDirectory(directory);
					else reconcileCheckpoint(id, properties);
				} catch (RuntimeException | IOException ignored) {
					deleteDirectory(directory);
				}
			}
		} catch (IOException ex) {
			throw new IllegalStateException("Could not clean expired transfer sessions", ex);
		}
	}

	private void reconcileCheckpoint(UUID id, Properties properties) {
		Path part = partPath(id);
		try {
			if (!Files.isRegularFile(part)) throw new IOException("Transfer data is missing");
			long offset = Long.parseLong(properties.getProperty("offset"));
			long actualSize = Files.size(part);
			if (actualSize < offset) throw new IOException("Transfer checkpoint exceeds stored data");
			if (actualSize > offset) {
				try (FileChannel file = FileChannel.open(part, StandardOpenOption.WRITE)) {
					file.truncate(offset);
					file.force(true);
				}
			}
		} catch (IOException | NumberFormatException ex) {
			throw new IllegalStateException("Could not recover transfer checkpoint", ex);
		}
	}

	private boolean matches(FileChannel file, long start, byte[] chunk) throws IOException {
		file.position(start);
		ByteBuffer stored = ByteBuffer.allocate(chunk.length);
		while (stored.hasRemaining() && file.read(stored) != -1) { }
		return stored.hasRemaining() == false && MessageDigest.isEqual(stored.array(), chunk);
	}

	private TransferStatus status(UUID id, Properties properties) {
		return new TransferStatus(id, Long.parseLong(properties.getProperty("offset")),
				Long.parseLong(properties.getProperty("size")), Instant.parse(properties.getProperty("expiresAt")),
				properties.getProperty("completed") != null);
	}

	private Properties load(UUID id) {
		Path state = directory(id).resolve(STATE_FILE);
		try {
			if (!Files.isRegularFile(state)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer not found");
			return read(state);
		} catch (IOException ex) {
			throw new IllegalStateException("Could not read transfer session", ex);
		}
	}

	private Properties read(Path path) throws IOException {
		Properties properties = new Properties();
		try (InputStream input = Files.newInputStream(path)) {
			properties.load(input);
		}
		return properties;
	}

	private void save(UUID id, Properties properties) throws IOException {
		Path temporary = directory(id).resolve(STATE_FILE + ".tmp");
		try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
			 var output = Channels.newOutputStream(channel)) {
			properties.store(output, null);
			output.flush();
			channel.force(true);
		}
		try {
			Files.move(temporary, directory(id).resolve(STATE_FILE), StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException ex) {
			Files.move(temporary, directory(id).resolve(STATE_FILE), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private Path directory(UUID id) {
		return transferRoot.resolve(id.toString()).normalize();
	}

	private Path partPath(UUID id) {
		return directory(id).resolve("content.part");
	}

	private String checksum(Path path) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream input = Files.newInputStream(path)) {
				byte[] buffer = new byte[8192];
				for (int count; (count = input.read(buffer)) != -1; ) digest.update(buffer, 0, count);
			}
			return java.util.HexFormat.of().formatHex(digest.digest());
		} catch (IOException | NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Could not verify transfer checksum", ex);
		}
	}

	private void deleteDirectory(Path directory) throws IOException {
		if (!Files.exists(directory)) return;
		try (Stream<Path> paths = Files.walk(directory)) {
			for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
		}
	}

	private String encode(String value) {
		return Base64.getEncoder().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private String decode(String value) {
		return new String(Base64.getDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8);
	}

	private ResponseStatusException badRequest(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}

	public record Upload(UUID id, Path path, String filename, String description, Set<String> tags,
			String sha256, String completedStorageKey) {
	}
}
