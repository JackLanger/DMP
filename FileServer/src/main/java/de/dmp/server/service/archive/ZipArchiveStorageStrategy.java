package de.dmp.server.service.archive;

import org.springframework.core.io.InputStreamResource;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class ZipArchiveStorageStrategy implements ArchiveStorageStrategy {

	@Override
	public String extension() {
		return ".zip";
	}

	@Override
	public boolean supports(Path source, String filename) {
		return Files.isRegularFile(source);
	}

	@Override
	public void write(Path source, String entryName, OutputStream output) throws IOException {
		try (var zip = new ZipOutputStream(output)) {
			zip.putNextEntry(new ZipEntry(entryName));
			Files.copy(source, zip);
			zip.closeEntry();
		}
	}

	@Override
	public boolean matches(Path archive, Path source, String entryName) throws IOException {
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			var entries = zip.entries();
			if (!entries.hasMoreElements()) return false;
			ZipEntry entry = entries.nextElement();
			if (!entry.getName().equals(entryName) || entries.hasMoreElements()) return false;
			try (var archived = zip.getInputStream(entry); var input = Files.newInputStream(source)) {
				return checksum(archived).equals(checksum(input));
			}
		}
	}

	@Override
	public Optional<ArchivedFile> read(Path archive) throws IOException {
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			var entries = zip.entries();
			if (!entries.hasMoreElements()) return Optional.empty();
			ZipEntry entry = entries.nextElement();
			String entryName = entry.getName();
			String sha256;
			try (InputStream input = openEntry(archive, entryName)) {
				sha256 = checksum(input);
			}
			return Optional.of(new ArchivedFile(
					new InputStreamResource(() -> openEntry(archive, entryName)), entry.getSize(), sha256));
		}
	}

	private InputStream openEntry(Path archive, String entryName) throws IOException {
		ZipFile zip = new ZipFile(archive.toFile());
		try {
			var entries = zip.entries();
			if (!entries.hasMoreElements()) throw new IOException("Archive entry is missing: " + entryName);
			ZipEntry entry = entries.nextElement();
			if (!entry.getName().equals(entryName)) throw new IOException("Archive entry has changed: " + entryName);
			return new FilterInputStream(zip.getInputStream(entry)) {
				@Override
				public void close() throws IOException {
					try {
						super.close();
					} finally {
						zip.close();
					}
				}
			};
		} catch (IOException | RuntimeException ex) {
			try {
				zip.close();
			} catch (IOException cleanup) {
				ex.addSuppressed(cleanup);
			}
			throw ex;
		}
	}

	private String checksum(InputStream input) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[8192];
			for (int count; (count = input.read(buffer)) != -1; ) digest.update(buffer, 0, count);
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}
}
