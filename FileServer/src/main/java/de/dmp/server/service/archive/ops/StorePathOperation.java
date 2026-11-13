package de.dmp.server.service.archive.ops;

import de.dmp.server.service.archive.ArchiveContext;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

@Slf4j
public record StorePathOperation(Path source, String filename, String storageKey) implements ArchiveOperation<String> {

    @Override
    public Mono<String> execute(ArchiveContext context, Scheduler scheduler) {
        return Mono.fromCallable(() -> archive(context))
                .subscribeOn(scheduler);
    }

    private String archive(ArchiveContext context) throws IOException {
        Path destination = context.archivePath(storageKey);
        Path temporary = Files.createTempFile(context.uploadDir(), "tmp", ".archive");
        try {

            try (var output = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING);
                 var zip = new ZipOutputStream(output)) {
                zip.putNextEntry(new ZipEntry(filename));
                Files.copy(source, zip);
                zip.closeEntry();
            }

            if (Files.exists(destination)) {
                if (!sameContent(destination, source, filename)) {
                    throw new java.nio.file.FileAlreadyExistsException(destination.toString());
                }
                Files.deleteIfExists(temporary);
                return storageKey;
            }

            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temporary, destination);
            } catch (java.nio.file.FileAlreadyExistsException ex) {
                if (!sameContent(destination, source, filename)) throw ex;
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

    private boolean sameContent(Path archive, Path source, String filename) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            if (!entries.hasMoreElements()) return false;
            ZipEntry entry = entries.nextElement();
            if (!entry.getName().equals(filename) || entries.hasMoreElements()) return false;
            try (var archived = zip.getInputStream(entry);
                 var sourceInput = Files.newInputStream(source)) {
                return checksum(archived).equals(checksum(sourceInput));
            }
        }
    }

    private String checksum(java.io.InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1; ) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
