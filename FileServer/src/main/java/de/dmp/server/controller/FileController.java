package de.dmp.server.controller;


import de.dmp.server.data.dto.MetadataDto;
import de.dmp.server.data.Metadata;
import de.dmp.server.service.MetadataService;
import de.dmp.server.service.ReactiveUploadMetadataService;
import de.dmp.server.service.archive.Archive;
import de.dmp.server.service.archive.ArchivedFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.http.*;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;


@Slf4j
@RestController
@RequestMapping("/files")
@RestControllerAdvice
public class FileController {

	private final MetadataService metadataService;

	private final Archive archive;
	private final ReactiveUploadMetadataService uploadMetadataService;


	@Autowired
	public FileController(MetadataService metadataService, Archive archive, ReactiveUploadMetadataService uploadMetadataService) {

		this.metadataService = metadataService;
		this.archive = archive;
		this.uploadMetadataService = uploadMetadataService;
	}

	@GetMapping("/all")
	public Mono<List<MetadataDto>> listFiles() {

		return Mono.fromCallable(metadataService::findAll).subscribeOn(Schedulers.boundedElastic());
	}

	@GetMapping("/tagged/{tag}")
	public Mono<List<MetadataDto>> listFilesByTag(@PathVariable("tag") String tagName) {

		return Mono.fromCallable(() -> metadataService.findByTagName(tagName)).subscribeOn(Schedulers.boundedElastic());
	}

	@GetMapping("/{id}")
	public Mono<Optional<MetadataDto>> getFileById(@PathVariable("id") UUID id) {

		return Mono.fromCallable(() -> metadataService.findById(id)).subscribeOn(Schedulers.boundedElastic());
	}

	@GetMapping("/{id}/download")
	public Mono<ResponseEntity<Resource>> download(@PathVariable("id") UUID id) {

		return Mono.fromCallable(() -> openDownload(id))
		           .subscribeOn(Schedulers.boundedElastic());
	}

	private ResponseEntity<Resource> openDownload(UUID id) throws ExecutionException, InterruptedException {

		final Optional<MetadataDto> metadata = metadataService.findById(id);
		if (metadata.isEmpty()) return ResponseEntity.notFound()
		                                             .build();

		Optional<ArchivedFile> archivedFile = archive.retrieveAsync(metadata.get())
		                                             .get();
		if (archivedFile.isEmpty()) return ResponseEntity.notFound()
		                                                 .build();

		final var response = ResponseEntity.ok()
		                                   .contentType(MediaType.APPLICATION_OCTET_STREAM)
		                                   .header(
				                                   HttpHeaders.CONTENT_DISPOSITION,
				                                   ContentDisposition.attachment()
				                                                     .filename(
						                                                     metadata.get()
						                                                             .name(),
						                                                     StandardCharsets.UTF_8
				                                                              )
				                                                     .build()
				                                                     .toString()
		                                          );

		if (archivedFile.get()
		                .size() >= 0) response.contentLength(archivedFile.get()
		                                                                 .size());

		return response.body(archivedFile.get()
		                                 .content());
	}

	@GetMapping("/named/{name}")
	public Mono<List<MetadataDto>> getFilesByName(@PathVariable("name") String name) {

		return Mono.fromCallable(() -> metadataService.where(
				() -> Criteria.where("name")
				              .regex(name, "i"))).subscribeOn(Schedulers.boundedElastic());
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Mono<ResponseEntity<String>> upload(
			@RequestPart("file") FilePart file,
			@RequestPart("metadata") UploadMetadataRequest metadata
	                                          ) {

		return Mono.defer(() -> archive.storeReactive(file)
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
				? Mono.empty() : uploadMetadataService.findById(metadata.uuid());
		return existing.flatMap(old -> {
			String oldStorageKey = old.getFileLocation();
			return uploadMetadataService.update(old, replacement)
					.flatMap(updated -> deleteArchive(oldStorageKey)
							.onErrorResume(ex -> {
								log.warn("Failed to delete archive for {}", oldStorageKey, ex);
								return Mono.empty();
							})
							.thenReturn(storageKey));
		}).switchIfEmpty(Mono.defer(() -> uploadMetadataService.create(replacement).thenReturn(storageKey)));
	}

	private Mono<Void> deleteArchive(String storageKey) {
		return Mono.fromRunnable(() -> {
			try {
				archive.delete(storageKey);
			} catch (IOException ex) {
				throw new java.io.UncheckedIOException(ex);
			}
		}).subscribeOn(Schedulers.boundedElastic()).then();
	}

}
