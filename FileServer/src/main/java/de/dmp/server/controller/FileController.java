package de.dmp.server.controller;


import de.dmp.server.data.dto.MetadataDto;
import de.dmp.server.data.Metadata;
import de.dmp.server.service.ReactiveMetadataService;
import de.dmp.server.service.archive.Archive;
import de.dmp.server.service.archive.ArchivedFile;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;


@Slf4j
@RestController
@RequestMapping("/files")
@RestControllerAdvice
public class FileController {

	private final Archive archive;
	private final ReactiveMetadataService metadataService;


	@Autowired
	public FileController(Archive archive, ReactiveMetadataService metadataService) {

		this.metadataService = metadataService;
		this.archive = archive;
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
	public Mono<ResponseEntity<Flux<DataBuffer>>> download(@PathVariable("id") UUID id) {

		return metadataService.findDtoById(id)
				.flatMap(metadata -> archive.retrieve(metadata)
						.map(archivedFile -> openDownload(metadata, archivedFile)))
				.defaultIfEmpty(ResponseEntity.notFound().build());
	}

	private ResponseEntity<Flux<DataBuffer>> openDownload(MetadataDto metadata, ArchivedFile archivedFile) {
		final var response = ResponseEntity.ok()
		                                   .contentType(MediaType.APPLICATION_OCTET_STREAM)
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

		if (archivedFile.size() >= 0) response.contentLength(archivedFile.size());

		return response.body(DataBufferUtils.readInputStream(archivedFile.content()::getInputStream,
				DefaultDataBufferFactory.sharedInstance, 8192).subscribeOn(Schedulers.boundedElastic()));
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
