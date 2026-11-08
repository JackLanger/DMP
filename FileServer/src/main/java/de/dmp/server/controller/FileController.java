package de.dmp.server.controller;


import de.dmp.server.data.dto.MetadataDto;
import de.dmp.server.service.MetadataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.http.*;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;


@Slf4j
@RestController
@RequestMapping("/files")
public class FileController {

	private final MetadataService metadataService;

	private final Path uploadDir;


	@Autowired
	public FileController(MetadataService metadataService) {

		this(metadataService, Path.of("e:/FileServer")); // TODO replace with configuration bean
	}


	FileController(MetadataService metadataService, Path uploadDir) {

		this.metadataService = metadataService;
		this.uploadDir = uploadDir;
	}


	private static void deleteArchive(Path uploadDir, String storageKey) {

		try {
			Files.deleteIfExists(uploadDir.resolve("%s.zip".formatted(storageKey)));
		} catch (IOException ex) {
			log.warn("Failed to delete archive for {}", storageKey, ex);
		}
	}


	private static void deleteTempFile(Path destination) {

		if (!destination.toFile()
		                .delete()) log.debug("Failed to clean File {}", destination.getFileName());
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


	private ResponseEntity<Resource> openDownload(UUID id) throws IOException {

		final Optional<MetadataDto> metadata = metadataService.findById(id);
		if (metadata.isEmpty()) return ResponseEntity.notFound()
		                                             .build();

		final Path root = uploadDir.toAbsolutePath()
		                           .normalize();
		final Path archive = root.resolve("%s.zip".formatted(metadata.get()
		                                                             .fileLocation()))
		                         .normalize();
		if (!archive.startsWith(root) || !Files.isRegularFile(archive)) {
			log.warn("Archive missing for file {}", id);
			return ResponseEntity.notFound()
			                     .build();
		}

		final ZipFile zip = new ZipFile(archive.toFile());
		try {
			final var entries = zip.entries();
			if (!entries.hasMoreElements()) {
				zip.close();
				return ResponseEntity.notFound()
				                     .build();
			}
			final ZipEntry entry = entries.nextElement();
			// closing the entry stream also closes the archive once the response is fully written
			final InputStream in = new FilterInputStream(zip.getInputStream(entry)) {

				@Override
				public void close() throws IOException {

					try {
						super.close();
					} finally {
						zip.close();
					}
				}
			};

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

			if (entry.getSize() >= 0) response.contentLength(entry.getSize());

			return response.body(new InputStreamResource(in));
		} catch (IOException | RuntimeException ex) {
			zip.close();
			throw ex;
		}
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

		final String storageKey = UUID.randomUUID()
		                              .toString(); // don't use the client filename as a path
		final Path destination = uploadDir.resolve("tmp%s".formatted(storageKey));
		final Path archive = uploadDir.resolve("%s.zip".formatted(storageKey));

		final AtomicBoolean persisting = new AtomicBoolean();
		final Runnable zipAndPersist = () -> {
			persisting.set(true);
			try {
				new Zip(destination, archive).run();
				new CreateOrUpdateFileEntry(file, metadata, storageKey, metadataService, uploadDir).run();
			} catch (RuntimeException ex) {
				deleteArchive(uploadDir, storageKey);
				throw ex;
			} finally {
				deleteTempFile(destination);
			}
		};

		// cache() keeps the task running to completion if the client cancels, so cleanup is owned by the task itself
		final Mono<Void> persist = Mono.<Void>fromRunnable(zipAndPersist)
		                               .subscribeOn(Schedulers.boundedElastic())
		                               .cache();

		return file.transferTo(destination)
		           .then(persist)
		           .thenReturn(ResponseEntity.status(HttpStatus.CREATED)
		                                     .body(storageKey))
		           .doFinally(_ -> {
			           if (!persisting.get()) deleteTempFile(destination);
		           });
	}


	private record CreateOrUpdateFileEntry(
			FilePart file, UploadMetadataRequest metadata, String storageKey,
			MetadataService metadataService, Path uploadDir
	) implements Runnable {

		public void run() {

			Optional<MetadataDto> existing = Optional.ofNullable(metadata.uuid())
			                                         .flatMap(metadataService::findById);
			MetadataDto replacement = new MetadataDto(file.name(), metadata.description(), storageKey, metadata.tags());

			if (existing.isPresent()) {
				metadataService.update(metadata.uuid(), replacement);
			} else {
				metadataService.create(replacement);
			}

			existing.ifPresent(old -> deleteArchive(uploadDir, old.fileLocation()));
		}

	}


	private record Zip(Path source, Path target) implements Runnable {

		@Override
		public void run() {

			try (var outputStream = Files.newOutputStream(target);
			     ZipOutputStream zip = new ZipOutputStream(outputStream)) {

				zip.putNextEntry(new ZipEntry(source.getFileName()
				                                    .toString()));
				Files.copy(source, zip);
				zip.closeEntry();

			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

	}

}
