package de.dmp.server.service.archive;

import de.dmp.server.data.dto.MetadataDto;
import de.dmp.server.service.archive.ops.DeleteOperation;
import de.dmp.server.service.archive.ops.RetrieveOperation;
import de.dmp.server.service.archive.ops.StoreOperation;
import org.springframework.http.codec.multipart.FilePart;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Future;

public class ArchiveService implements Archive {

	private static final int DEFAULT_WORKERS = 2;
	private final ArchiveContext context;
	private final ArchiveMaster master;

	public ArchiveService(Path uploadDir) throws IOException {

		this(uploadDir, DEFAULT_WORKERS);
	}

	public ArchiveService(Path uploadDir, int workers) throws IOException {

		this.context = ArchiveContext.create(uploadDir);
		this.master = new ArchiveMaster(context, workers);
	}

	@Override
	public Path getUploadDir() {

		return context.uploadDir();
	}

	@Override
	public Future<String> storeAsync(FilePart file) {

		return master.submit(new StoreOperation(file));
	}

	@Override
	public String store(FilePart file) throws IOException {

		return new StoreOperation(file).execute(context);
	}

	@Override
	public void delete(String storageKey) throws IOException {

		new DeleteOperation(storageKey).execute(context);
	}

	@Override
	public Future<Optional<ArchivedFile>> retrieveAsync(MetadataDto metadataDto) {

		return master.submit(new RetrieveOperation(metadataDto));
	}

	@Override
	public Optional<ArchivedFile> retrieve(MetadataDto metadataDto) throws IOException {

		return new RetrieveOperation(metadataDto).execute(context);
	}

	@Override
	public void close() {

		master.close();
	}

}
