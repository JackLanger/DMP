package de.dmp.server.service.archive.ops;

import de.dmp.server.data.dto.MetadataDto;
import de.dmp.server.service.archive.ArchiveContext;
import de.dmp.server.service.archive.ArchivedFile;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.io.IOException;
import java.util.Optional;

@Slf4j
public record RetrieveOperation(MetadataDto metadata) implements ArchiveOperation<ArchivedFile> {

	@Override
	public Mono<ArchivedFile> execute(ArchiveContext context, Scheduler scheduler) {
		return Mono.fromCallable(() -> retrieve(context))
				.subscribeOn(scheduler)
				.flatMap(Mono::justOrEmpty);
	}

	private Optional<ArchivedFile> retrieve(ArchiveContext context) throws IOException {
		var archive = context.findArchive(metadata.fileLocation());
		if (archive.isEmpty()) {
			log.warn("Archive missing for file {}", metadata.id());
			return Optional.empty();
		}
		return archive.get().strategy().read(archive.get().path());
	}
}
