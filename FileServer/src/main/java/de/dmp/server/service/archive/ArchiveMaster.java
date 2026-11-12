package de.dmp.server.service.archive;

import de.dmp.server.service.archive.ops.ArchiveOperation;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class ArchiveMaster implements AutoCloseable {

	private final ArchiveContext context;
	private final ExecutorService executor;

	public ArchiveMaster(ArchiveContext context, int workers) {

		this.context = Objects.requireNonNull(context, "context");
		if (workers < 1) {
			throw new IllegalArgumentException("Archive worker count must be positive");
		}
		this.executor = Executors.newFixedThreadPool(workers);
	}

	public <T> Future<T> submit(ArchiveOperation<T> operation) {

		Objects.requireNonNull(operation, "operation");
		return executor.submit(() -> operation.execute(context));
	}

	@Override
	public void close() {

		executor.shutdown();
	}

}
