package de.dmp.server.service.archive.ops;

import de.dmp.server.service.archive.ArchiveContext;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.nio.file.Files;

public record DeleteOperation(String storageKey) implements ArchiveOperation<Void> {

	@Override
	public Mono<Void> execute(ArchiveContext context, Scheduler scheduler) {
		return Mono.fromCallable(() -> {
			var archive = context.findArchive(storageKey);
			return archive.isPresent() && Files.deleteIfExists(archive.get().path());
		})
				.subscribeOn(scheduler).then();
	}

}
