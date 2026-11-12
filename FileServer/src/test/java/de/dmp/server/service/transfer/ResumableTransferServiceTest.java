package de.dmp.server.service.transfer;

import de.dmp.server.controller.ResumableUploadRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ResumableTransferServiceTest {

	@TempDir
	Path uploadDir;

	private static String sha256(byte[] content) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
	}

	private ResumableUploadRequest request(byte[] content) throws Exception {
		return new ResumableUploadRequest("report.txt", content.length, sha256(content),
				"description", Set.of("research"));
	}

	@Test
	void resumesAfterRestartAndAcceptsOnlyIdenticalChunkRetries() throws Exception {
		byte[] contents = "resumable document".getBytes();
		ResumableTransferService first = new ResumableTransferService(uploadDir, Duration.ofHours(1));
		UUID id = first.create(request(contents)).transferId();
		byte[] firstChunk = java.util.Arrays.copyOfRange(contents, 0, 7);
		assertEquals(7, first.append(id, 0, 6, contents.length, firstChunk).offset());

		ResumableTransferService restarted = new ResumableTransferService(uploadDir, Duration.ofHours(1));
		assertEquals(7, restarted.status(id).offset());
		assertEquals(7, restarted.append(id, 0, 6, contents.length, firstChunk).offset());
		ResponseStatusException mismatch = assertThrows(ResponseStatusException.class,
				() -> restarted.append(id, 0, 6, contents.length, "changed".getBytes()));
		assertEquals(HttpStatus.CONFLICT, mismatch.getStatusCode());

		byte[] finalChunk = java.util.Arrays.copyOfRange(contents, 7, contents.length);
		assertEquals(contents.length, restarted.append(id, 7, contents.length - 1, contents.length, finalChunk).offset());
		ResumableTransferService.Upload upload = restarted.complete(id);
		assertEquals(contents.length, java.nio.file.Files.size(upload.path()));
		assertEquals(sha256(contents), upload.sha256());
		assertEquals(Set.of("research"), upload.tags());
		restarted.markCompleted(id, id.toString());
		assertEquals(id.toString(), restarted.complete(id).completedStorageKey());
	}

	@Test
	void rejectsIncompleteAndCorruptTransfers() throws Exception {
		byte[] contents = "integrity".getBytes();
		ResumableTransferService service = new ResumableTransferService(uploadDir, Duration.ofHours(1));
		UUID id = service.create(request(contents)).transferId();
		ResponseStatusException incomplete = assertThrows(ResponseStatusException.class, () -> service.complete(id));
		assertEquals(HttpStatus.CONFLICT, incomplete.getStatusCode());

		service.append(id, 0, contents.length - 1, contents.length, "corrupt!!".getBytes());
		ResponseStatusException corrupt = assertThrows(ResponseStatusException.class, () -> service.complete(id));
		assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, corrupt.getStatusCode());
	}

	@Test
	void removesExpiredSessions() throws Exception {
		byte[] contents = "expired".getBytes();
		ResumableTransferService service = new ResumableTransferService(uploadDir, Duration.ofMillis(1));
		UUID id = service.create(request(contents)).transferId();
		Thread.sleep(20);

		service.cleanupExpired();

		ResponseStatusException missing = assertThrows(ResponseStatusException.class, () -> service.status(id));
		assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
	}
}
