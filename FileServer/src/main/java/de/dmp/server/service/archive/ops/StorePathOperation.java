package de.dmp.server.service.archive.ops;

import de.dmp.server.service.archive.ArchiveContext;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public record StorePathOperation(Path source, String filename, String storageKey) implements ArchiveOperation<String> {

    @Override
    public Mono<String> execute(ArchiveContext context, Scheduler scheduler) {
        return Mono.fromCallable(() -> archive(context))
                .subscribeOn(scheduler);
    }

    private String archive(ArchiveContext context) throws IOException {
        var existing = context.findArchive(storageKey);
        if (existing.isPresent()) {
            var archive = existing.get();
            if (!archive.strategy().matches(archive.path(), source, filename)) {
                throw new java.nio.file.FileAlreadyExistsException(archive.path().toString());
            }
            return storageKey;
        }
        var strategy = context.strategyFor(source, filename);
        Path destination = context.archivePath(storageKey, strategy);
        Path temporary = Files.createTempFile(context.uploadDir(), "tmp", ".archive");
        try {

            try (var output = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)) {
                strategy.write(source, filename, output);
            }

            existing = context.findArchive(storageKey);
            if (existing.isPresent()) {
                var archive = existing.get();
                if (!archive.strategy().matches(archive.path(), source, filename)) {
                    throw new java.nio.file.FileAlreadyExistsException(archive.path().toString());
                }
                Files.deleteIfExists(temporary);
                return storageKey;
            }

            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temporary, destination);
            } catch (java.nio.file.FileAlreadyExistsException ex) {
                if (!strategy.matches(destination, source, filename)) throw ex;
                Files.deleteIfExists(temporary);
            }

            return storageKey;
        } catch (IOException | RuntimeException ex) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                ex.addSuppressed(cleanup);
            }
            throw ex;
        }
    }

}
