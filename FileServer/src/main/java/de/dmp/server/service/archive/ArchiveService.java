package de.dmp.server.service.archive;

import de.dmp.server.data.dto.MetadataDto;
import de.dmp.server.service.archive.ops.ArchiveOperation;
import de.dmp.server.service.archive.ops.DeleteOperation;
import de.dmp.server.service.archive.ops.RetrieveOperation;
import de.dmp.server.service.archive.ops.StoreOperation;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Path;

public class ArchiveService implements Archive {

	private static final int DEFAULT_WORKERS = 2;
	private final ArchiveContext context;
	private final Scheduler scheduler;

	public ArchiveService(Path uploadDir) throws IOException {

		this(uploadDir, DEFAULT_WORKERS);
	}

	public ArchiveService(Path uploadDir, int workers) throws IOException {

		if (workers < 1) throw new IllegalArgumentException("Archive worker count must be positive");
		this.context = ArchiveContext.create(uploadDir);
		this.scheduler = Schedulers.newBoundedElastic(workers, 100000, "archive");
	}

	@Override
	public Path getUploadDir() {

		return context.uploadDir();
	}

	@Override
	public Mono<String> store(FilePart file) {
		return execute(new StoreOperation(file));
	}

	@Override
	public Mono<Void> delete(String storageKey) {
		return execute(new DeleteOperation(storageKey));
	}

	@Override
	public Mono<ArchivedFile> retrieve(MetadataDto metadataDto) {
		return execute(new RetrieveOperation(metadataDto));
	}

	private <T> Mono<T> execute(ArchiveOperation<T> operation) {
		return operation.execute(context, scheduler);
	}

	@Override
	public void close() {

		scheduler.dispose();
	}

}
