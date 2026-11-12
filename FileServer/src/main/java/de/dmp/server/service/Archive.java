package de.dmp.server.service;

import de.dmp.server.data.dto.MetadataDto;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.zip.ZipFile;

public interface Archive {

	Path getUploadDir();

	Future<ZipFile> retrieveAsync(MetadataDto metadataDto);

	Optional<ZipFile> retrieve(MetadataDto metadataDto) throws IOException;

}
