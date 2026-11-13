package de.dmp.server.controller;


import de.dmp.server.data.dto.MetadataDto;
import de.dmp.server.data.Metadata;
import de.dmp.server.service.ReactiveMetadataService;
import de.dmp.server.service.archive.Archive;
import de.dmp.server.service.archive.ArchivedFile;
import de.dmp.server.service.transfer.ResumableTransferService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.*;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Slf4j
@RestController
@RequestMapping("/files")
@RestControllerAdvice
public class FileController {

	private final Archive archive;
	private final ReactiveMetadataService metadataService;
	private final ResumableTransferService transfers;

	@Autowired
	public FileController(Archive archive, ReactiveMetadataService metadataService, ResumableTransferService transfers) {
		this.metadataService = metadataService;
		this.archive = archive;
		this.transfers = transfers;
	}

	@GetMapping("/all")
	public Mono<List<MetadataDto>> listFiles() {

		return metadataService.findAll().collectList();
	}

	@GetMapping("/tagged/{tag}")
	public Mono<List<MetadataDto>> listFilesByTag(@PathVariable("tag") String tagName) {

		return metadataService.findByTagName(tagName).collectList();
	}

	@GetMapping("/{id}")
	public Mono<Optional<MetadataDto>> getFileById(@PathVariable("id") UUID id) {

		return metadataService.findDtoById(id).map(Optional::of).defaultIfEmpty(Optional.empty());
	}

	@GetMapping("/{id}/download")
	public Mono<ResponseEntity<Flux<DataBuffer>>> download(
			@PathVariable("id") UUID id,
			@RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
			@RequestHeader(value = HttpHeaders.IF_RANGE, required = false) String ifRange) {

		return metadataService.findDtoById(id)
				.flatMap(metadata -> archive.retrieve(metadata)
						.map(archivedFile -> openDownload(metadata, archivedFile, range, ifRange)))
				.defaultIfEmpty(ResponseEntity.notFound().build());
	}

	private ResponseEntity<Flux<DataBuffer>> openDownload(
			MetadataDto metadata, ArchivedFile archivedFile, String rangeHeader, String ifRange) {
		long size = archivedFile.size();
		String etag = archivedFile.sha256() == null ? null : "\"" + archivedFile.sha256() + "\"";
		boolean useRange = rangeHeader != null && size >= 0 && (ifRange == null || ifRange.equals(etag));
		Range range = null;
		if (useRange) {
			try {
				range = parseRange(rangeHeader, size);
			} catch (IllegalArgumentException ex) {
				return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
						.header(HttpHeaders.CONTENT_RANGE, "bytes */" + size)
						.header(HttpHeaders.ACCEPT_RANGES, "bytes")
						.build();
			}
		}
		var response = ResponseEntity.status(range == null ? HttpStatus.OK : HttpStatus.PARTIAL_CONTENT)
		                                   .contentType(MediaType.APPLICATION_OCTET_STREAM)
		                                   .header(HttpHeaders.ACCEPT_RANGES, "bytes")
		                                   .header(
				                                   HttpHeaders.CONTENT_DISPOSITION,
				                                   ContentDisposition.attachment()
				                                                     .filename(
						                                                     metadata.name(),
						                                                     StandardCharsets.UTF_8
				                                                              )
				                                                     .build()
				                                                     .toString()
		                                          );

		if (etag != null) response.eTag(etag);
		if (size >= 0) response.contentLength(range == null ? size : range.length());
		if (range != null) response.header(HttpHeaders.CONTENT_RANGE,
				"bytes " + range.start() + "-" + range.end() + "/" + size);

		Range selectedRange = range;
		Flux<DataBuffer> content = DataBufferUtils.readInputStream(() -> {
			InputStream input = archivedFile.content().getInputStream();
			if (selectedRange == null) return input;
			input.skipNBytes(selectedRange.start());
			return new LimitedInputStream(input, selectedRange.length());
		}, DefaultDataBufferFactory.sharedInstance, 8192).subscribeOn(Schedulers.boundedElastic());
		return response.body(content);
	}

	private Range parseRange(String header, long size) {
		Matcher matcher = Pattern.compile("^bytes=(\\d*)-(\\d*)$").matcher(header);
		if (!matcher.matches() || size == 0) throw new IllegalArgumentException("Invalid range");
		long start;
		long end;
		try {
			if (matcher.group(1).isEmpty()) {
				long suffix = Long.parseLong(matcher.group(2));
				if (suffix <= 0) throw new IllegalArgumentException("Invalid suffix range");
				start = Math.max(0, size - suffix);
				end = size - 1;
			} else {
				start = Long.parseLong(matcher.group(1));
				end = matcher.group(2).isEmpty() ? size - 1 : Long.parseLong(matcher.group(2));
				if (start >= size || end < start) throw new IllegalArgumentException("Unsatisfiable range");
				end = Math.min(end, size - 1);
			}
		} catch (NumberFormatException ex) {
			throw new IllegalArgumentException("Invalid range", ex);
		}
		return new Range(start, end);
	}

	private record Range(long start, long end) {
		long length() {
			return end - start + 1;
		}
	}

	private static final class LimitedInputStream extends FilterInputStream {
		private long remaining;

		private LimitedInputStream(InputStream input, long limit) {
			super(input);
			remaining = limit;
		}

		@Override
		public int read() throws IOException {
			if (remaining == 0) return -1;
			int value = super.read();
			if (value != -1) remaining--;
			return value;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			if (length == 0) return 0;
			if (remaining == 0) return -1;
			int count = super.read(buffer, offset, (int) Math.min(length, remaining));
			if (count > 0) remaining -= count;
			return count;
		}
	}

	@PostMapping("/transfers")
	public Mono<ResponseEntity<TransferStatus>> createTransfer(@RequestBody ResumableUploadRequest request) {
		return Mono.fromCallable(() -> transfers.create(request))
				.subscribeOn(Schedulers.boundedElastic())
				.map(status -> ResponseEntity.status(HttpStatus.CREATED)
						.location(java.net.URI.create("/files/transfers/" + status.transferId()))
						.header("Upload-Offset", Long.toString(status.offset()))
						.header("Upload-Length", Long.toString(status.length()))
						.body(status));
	}

	@RequestMapping(value = "/transfers/{id}", method = {RequestMethod.GET, RequestMethod.HEAD})
	public Mono<ResponseEntity<TransferStatus>> transferStatus(@PathVariable UUID id) {
		return Mono.fromCallable(() -> transfers.status(id))
				.subscribeOn(Schedulers.boundedElastic())
				.map(status -> ResponseEntity.ok()
						.header("Upload-Offset", Long.toString(status.offset()))
						.header("Upload-Length", Long.toString(status.length()))
						.header("Upload-Expires", status.expiresAt().toString())
						.body(status));
	}

	@PatchMapping(value = "/transfers/{id}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
	public Mono<ResponseEntity<Void>> appendTransferChunk(@PathVariable UUID id,
			@RequestHeader(HttpHeaders.CONTENT_RANGE) String contentRange, @RequestBody byte[] chunk) {
		Matcher matcher = Pattern.compile("^bytes (\\d+)-(\\d+)/(\\d+)$").matcher(contentRange);
		if (!matcher.matches()) {
			return Mono.error(new org.springframework.web.server.ResponseStatusException(
					HttpStatus.BAD_REQUEST, "Content-Range must use bytes start-end/total"));
		}
		return Mono.fromCallable(() -> transfers.append(id, Long.parseLong(matcher.group(1)),
						Long.parseLong(matcher.group(2)), Long.parseLong(matcher.group(3)), chunk))
				.onErrorMap(NumberFormatException.class, error -> new org.springframework.web.server.ResponseStatusException(
						HttpStatus.BAD_REQUEST, "Content-Range values exceed the supported size", error))
				.subscribeOn(Schedulers.boundedElastic())
				.map(status -> ResponseEntity.noContent()
						.header("Upload-Offset", Long.toString(status.offset())).build());
	}

	@PostMapping("/transfers/{id}/complete")
	public Mono<ResponseEntity<TransferCompletion>> completeTransfer(@PathVariable UUID id) {
		return Mono.fromCallable(() -> transfers.complete(id))
				.subscribeOn(Schedulers.boundedElastic())
				.flatMap(upload -> {
					String storageKey = id.toString();
					if (upload.completedStorageKey() != null) {
						return Mono.just(ResponseEntity.ok(new TransferCompletion(id, id, upload.sha256())));
					}
					if (!transfers.beginFinalization(id)) {
						return Mono.error(new org.springframework.web.server.ResponseStatusException(
								HttpStatus.CONFLICT, "Transfer is already being completed"));
					}
					MetadataDto metadata = new MetadataDto(id, upload.filename(), upload.description(), storageKey,
							Instant.now(), upload.tags());
					Mono<Metadata> persisted = metadataService.findById(id)
							.flatMap(existing -> {
								if (!existing.getFileLocation().equals(storageKey)) {
									return Mono.error(new org.springframework.web.server.ResponseStatusException(
											HttpStatus.CONFLICT, "Transfer ID is already in use"));
								}
								return archive.store(upload.path(), upload.filename(), storageKey).thenReturn(existing);
							})
							.switchIfEmpty(Mono.defer(() -> storeCompletedUpload(upload, metadata, storageKey)))
							.switchIfEmpty(Mono.error(new IllegalStateException("Metadata creation returned no result")));
					return persisted
							.flatMap(ignored -> Mono.fromRunnable(() -> transfers.markCompleted(id, storageKey))
									.subscribeOn(Schedulers.boundedElastic()))
							.thenReturn(ResponseEntity.ok(new TransferCompletion(id, id, upload.sha256())))
							.doFinally(ignored -> transfers.endFinalization(id));
				});
	}

	private Mono<Metadata> storeCompletedUpload(
			ResumableTransferService.Upload upload, MetadataDto metadata, String storageKey) {
		return archive.store(upload.path(), upload.filename(), storageKey)
				.then(metadataService.create(metadata).onErrorResume(error -> archive.delete(storageKey)
						.onErrorResume(cleanup -> {
							error.addSuppressed(cleanup);
							return Mono.empty();
						}).then(Mono.error(error))));
	}

	@DeleteMapping("/transfers/{id}")
	public Mono<ResponseEntity<Void>> deleteTransfer(@PathVariable UUID id) {
		return Mono.fromRunnable(() -> transfers.delete(id))
				.subscribeOn(Schedulers.boundedElastic())
				.thenReturn(ResponseEntity.noContent().build());
	}

	@GetMapping("/named/{name}")
	public Mono<List<MetadataDto>> getFilesByName(@PathVariable("name") String name) {

		return metadataService.findByName(name).collectList();
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Mono<ResponseEntity<String>> upload(
			@RequestPart("file") FilePart file,
			@RequestPart("metadata") UploadMetadataRequest metadata
	                                          ) {

		return Mono.defer(() -> archive.store(file)
				.flatMap(storageKey -> Mono.defer(() -> persistUpload(file, metadata, storageKey))
						.onErrorResume(ex -> deleteArchive(storageKey)
								.onErrorResume(cleanup -> {
									ex.addSuppressed(cleanup);
									return Mono.empty();
								})
								.then(Mono.error(ex))))
				.map(storageKey -> ResponseEntity.status(HttpStatus.CREATED).body(storageKey)))
				.cache();
	}

	private Mono<String> persistUpload(FilePart file, UploadMetadataRequest metadata, String storageKey) {
		MetadataDto replacement = new MetadataDto(file.filename(), metadata.description(), storageKey, metadata.tags());
		Mono<Metadata> existing = metadata.uuid() == null
				? Mono.empty() : metadataService.findById(metadata.uuid());
		return existing.flatMap(old -> {
			String oldStorageKey = old.getFileLocation();
			return metadataService.update(old, replacement)
					.flatMap(updated -> deleteArchive(oldStorageKey)
							.onErrorResume(ex -> {
								log.warn("Failed to delete archive for {}", oldStorageKey, ex);
								return Mono.empty();
							})
							.thenReturn(storageKey));
		}).switchIfEmpty(Mono.defer(() -> metadataService.create(replacement).thenReturn(storageKey)));
	}

	private Mono<Void> deleteArchive(String storageKey) {
		return archive.delete(storageKey);
	}

}
