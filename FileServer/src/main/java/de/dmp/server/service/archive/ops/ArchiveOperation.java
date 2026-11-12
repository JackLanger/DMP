package de.dmp.server.service.archive.ops;

import de.dmp.server.service.archive.ArchiveContext;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

@FunctionalInterface
public interface ArchiveOperation<T> {

	/**
	 * Creates a reactive operation, scheduling blocking archive I/O on the supplied scheduler.
	 */
	Mono<T> execute(ArchiveContext context, Scheduler scheduler);

}
