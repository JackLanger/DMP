package de.dmp.server.controller;


import de.dmp.server.data.dto.MetadataDto;
import de.dmp.server.service.Archive;
import de.dmp.server.service.ArchivedFile;
import de.dmp.server.service.MetadataService;
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
public class FileController {

	private final MetadataService metadataService;

	private final Archive archive;


	@Autowired
	public FileController(MetadataService metadataService, Archive archive) {

		this.metadataService = metadataService;
		this.archive = archive;
	}

	@GetMapping("/all")
	public List<MetadataDto> listFiles() {

		return metadataService.findAll();
	}

	@GetMapping("/tagged/{tag}")
	public List<MetadataDto> listFilesByTag(@PathVariable("tag") String tagName) {

		return metadataService.findByTagName(tagName);
	}

	@GetMapping("/{id}")
	public Optional<MetadataDto> getFileById(@PathVariable("id") UUID id) {

		return metadataService.findById(id);
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

		Optional<ArchivedFile> archivedFile = archive.retrieveAsync(metadata.get()).get();
		if (archivedFile.isEmpty()) return ResponseEntity.notFound().build();

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

		if (archivedFile.get().size() >= 0) response.contentLength(archivedFile.get().size());

		return response.body(archivedFile.get().content());
	}

	@GetMapping("/named/{name}")
	public List<MetadataDto> getFilesByName(@PathVariable("name") String name) {

		return metadataService.where(
				() -> Criteria.where("name")
				              .regex(name, "i"));
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Mono<ResponseEntity<String>> upload(
			@RequestPart("file") FilePart file,
			@RequestPart("metadata") UploadMetadataRequest metadata
	                                          ) {

		// Do not cancel the storage and metadata task when the response subscriber disconnects.
		return Mono.fromCallable(() -> persistUpload(file, metadata))
		           .subscribeOn(Schedulers.boundedElastic())
		           .cache();
	}

	private ResponseEntity<String> persistUpload(FilePart file, UploadMetadataRequest metadata)
			throws ExecutionException, InterruptedException {

		String storageKey = archive.storeAsync(file)
		                           .get();
		Optional<MetadataDto> existing;
		try {
			existing = Optional.ofNullable(metadata.uuid())
			                   .flatMap(metadataService::findById);
			MetadataDto replacement = new MetadataDto(file.name(), metadata.description(), storageKey, metadata.tags());
			if (existing.isPresent()) {
				metadataService.update(metadata.uuid(), replacement);
			} else {
				metadataService.create(replacement);
			}
		} catch (RuntimeException ex) {
			try {
				archive.delete(storageKey);
			} catch (IOException cleanup) {
				ex.addSuppressed(cleanup);
			}
			throw ex;
		}
		existing.ifPresent(old -> {
			try {
				archive.delete(old.fileLocation());
			} catch (IOException ex) {
				log.warn("Failed to delete archive for {}", old.fileLocation(), ex);
			}
		});

		return ResponseEntity.status(HttpStatus.CREATED)
		                     .body(storageKey);
	}

}
