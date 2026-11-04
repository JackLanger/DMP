package de.dmp.server.controller;


import de.dmp.server.data.Metadata;
import de.dmp.server.data.dto.MetadataDto;
import de.dmp.server.service.MetadataService;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;


class FileControllerTest {

  @TempDir
  Path uploadDir;

  private MetadataService metadataService;

  private FileController controller;


  private static MetadataDto metadata(UUID id, String name, String fileLocation) {

    return new MetadataDto(id, name, "description", fileLocation, Instant.now(), Set.of());
  }


  private static FilePart filePart(String name, byte[] contents) {

    FilePart file = mock(FilePart.class);
    when(file.name()).thenReturn(name);
    when(file.transferTo(any(Path.class))).thenAnswer(invocation -> Mono.fromRunnable(() -> {
      try {
        Files.write(invocation.getArgument(0), contents);
      } catch (IOException ex) {
        throw new UncheckedIOException(ex);
      }
    }));
    return file;
  }


  private static void writeArchive(Path path, byte[] contents) throws IOException {

    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
      zip.putNextEntry(new ZipEntry("report.txt"));
      zip.write(contents);
      zip.closeEntry();
    }
  }


  @SuppressWarnings("all")
  private static void writeArchive(Path path) throws IOException {

    try (ZipOutputStream ignored = new ZipOutputStream(Files.newOutputStream(path))) {
      // no touchy
    }
  }


  private static void assertArchiveContains(Path archive, byte[] expected) throws IOException {

    try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
      assertNotNull(zip.getNextEntry());
      assertArrayEquals(expected, zip.readAllBytes());
    }
  }


  @BeforeEach
  void setUp() {

    metadataService = mock(MetadataService.class);
    controller = new FileController(metadataService, uploadDir);
  }


  @Nested
  class ListFiles {

    @Test
    void returnsAllMetadata() {

      List<MetadataDto> expected = List.of(metadata(UUID.randomUUID(), "report.pdf", "storage-key"));
      when(metadataService.findAll()).thenReturn(expected);

      assertSame(expected, controller.listFiles());
      verify(metadataService).findAll();
    }

  }


  @Nested
  class ListFilesByTag {

    @Test
    void filtersMetadataByTagName() {

      List<MetadataDto> expected = List.of(metadata(UUID.randomUUID(), "report.pdf", "storage-key"));
      when(metadataService.findByTagName("research")).thenReturn(expected);

      assertSame(expected, controller.listFilesByTag("research"));
      verify(metadataService).findByTagName("research");
    }

  }


  @Nested
  class GetFileById {

    @Test
    void returnsMetadataForId() {

      UUID id = UUID.randomUUID();
      MetadataDto expected = metadata(id, "report.pdf", "storage-key");
      when(metadataService.findById(id)).thenReturn(Optional.of(expected));

      assertEquals(Optional.of(expected), controller.getFileById(id));
      verify(metadataService).findById(id);
    }


    @Test
    void returnsEmptyWhenIdDoesNotExist() {

      UUID id = UUID.randomUUID();
      when(metadataService.findById(id)).thenReturn(Optional.empty());

      assertEquals(Optional.empty(), controller.getFileById(id));
      verify(metadataService).findById(id);
    }

  }


  @Nested
  class Download {

    @Test
    void streamsFirstEntryOfArchiveWithDownloadHeaders() throws IOException {

      UUID id = UUID.randomUUID();
      byte[] contents = "file contents".getBytes();
      when(metadataService.findById(id)).thenReturn(Optional.of(metadata(id, "report.txt", "archive-key")));
      writeArchive(uploadDir.resolve("archive-key.zip"), contents);

      ResponseEntity<Resource> response = controller.download(id)
                                                    .block(Duration.ofSeconds(5));

      assertNotNull(response);
      assertEquals(HttpStatus.OK, response.getStatusCode());
      assertEquals(
          MediaType.APPLICATION_OCTET_STREAM,
          response.getHeaders()
                  .getContentType()
      );
      assertEquals(
          contents.length,
          response.getHeaders()
                  .getContentLength()
      );
      assertEquals(
          "report.txt",
          response.getHeaders()
                  .getContentDisposition()
                  .getFilename()
      );
      assert response.getBody() != null;
      try (var inputStream = response.getBody()
                                     .getInputStream()) {
        assertArrayEquals(contents, inputStream.readAllBytes());
      }
      verify(metadataService).findById(id);
    }


    @Test
    void returnsNotFoundWhenMetadataDoesNotExist() {

      UUID id = UUID.randomUUID();
      when(metadataService.findById(id)).thenReturn(Optional.empty());

      ResponseEntity<Resource> response = controller.download(id)
                                                    .block(Duration.ofSeconds(5));

      assertNotNull(response);
      assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
      verify(metadataService).findById(id);
    }


    @Test
    void returnsNotFoundWhenArchiveIsMissing() {

      UUID id = UUID.randomUUID();
      when(metadataService.findById(id)).thenReturn(Optional.of(metadata(id, "report.txt", "missing-archive")));

      ResponseEntity<Resource> response = controller.download(id)
                                                    .block(Duration.ofSeconds(5));

      assertNotNull(response);
      assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }


    @Test
    void returnsNotFoundWhenArchiveIsEmpty() throws IOException {

      UUID id = UUID.randomUUID();
      when(metadataService.findById(id)).thenReturn(Optional.of(metadata(id, "report.txt", "empty-archive")));
      writeArchive(uploadDir.resolve("empty-archive.zip"));

      ResponseEntity<Resource> response = controller.download(id)
                                                    .block(Duration.ofSeconds(5));

      assertNotNull(response);
      assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

  }


  @Nested
  @SuppressWarnings("unchecked")
  class GetFilesByName {

    @Test
    void returnsMetadataMatchingNameQuery() {

      List<MetadataDto> expected = List.of(metadata(UUID.randomUUID(), "report.pdf", "storage-key"));
      when(metadataService.where(any())).thenReturn(expected);

      assertSame(expected, controller.getFilesByName("report%"));

      var specification = org.mockito.ArgumentCaptor.forClass(Specification.class);
      verify(metadataService).where(specification.capture());
      @SuppressWarnings("unchecked")
      Root<Metadata> root = mock(Root.class);
      CriteriaQuery<Metadata> query = mock(CriteriaQuery.class);
      CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
      jakarta.persistence.criteria.Path<String> namePath = mock(jakarta.persistence.criteria.Path.class);
      Predicate predicate = mock(Predicate.class);
      when(root.<String>get("name")).thenReturn(namePath);
      when(criteriaBuilder.like(namePath, "report%")).thenReturn(predicate);
      when(query.where(predicate)).thenReturn(query);
      when(query.getRestriction()).thenReturn(predicate);

      assertSame(
          predicate,
          specification.getValue()
                       .toPredicate(root, query, criteriaBuilder)
      );
      verify(criteriaBuilder).like(namePath, "report%");
      verify(query).where(predicate);
    }

  }


  @Nested
  class Upload {

    @Test
    void createsMetadataAndArchiveForNewUpload() throws IOException {

      byte[] contents = "upload contents".getBytes();
      FilePart file = filePart("report.txt", contents);
      UploadMetadataRequest request = new UploadMetadataRequest(null, "description", Set.of("research"));

      ResponseEntity<String> response = controller.upload(file, request)
                                                  .block(Duration.ofSeconds(5));

      assertNotNull(response);
      assertEquals(HttpStatus.CREATED, response.getStatusCode());
      assertNotNull(response.getBody());
      var created = org.mockito.ArgumentCaptor.forClass(MetadataDto.class);
      verify(metadataService).create(created.capture());
      assertEquals(
          "report.txt",
          created.getValue()
                 .name()
      );
      assertEquals(
          "description",
          created.getValue()
                 .description()
      );
      assertEquals(
          Set.of("research"),
          created.getValue()
                 .tags()
      );
      assertArchiveContains(uploadDir.resolve(response.getBody() + ".zip"), contents);
      assertFalse(Files.exists(uploadDir.resolve("tmp" + response.getBody())));
      verify(metadataService, never()).update(any(), any());
    }


    @Test
    void updatesMetadataAndRemovesReplacedArchive() throws IOException {

      UUID id = UUID.randomUUID();
      byte[] contents = "replacement contents".getBytes();
      FilePart file = filePart("replacement.txt", contents);
      MetadataDto oldMetadata = metadata(id, "old.txt", "old-storage-key");
      when(metadataService.findById(id)).thenReturn(Optional.of(oldMetadata));
      Files.createFile(uploadDir.resolve("old-storage-key.zip"));

      ResponseEntity<String> response = controller.upload(
                                                      file,
                                                      new UploadMetadataRequest(id, "updated description", Set.of("updated"))
                                                  )
                                                  .block(Duration.ofSeconds(5));

      assertNotNull(response);
      assertEquals(HttpStatus.CREATED, response.getStatusCode());
      var updated = org.mockito.ArgumentCaptor.forClass(MetadataDto.class);
      verify(metadataService).update(eq(id), updated.capture());
      assertEquals(
          "replacement.txt",
          updated.getValue()
                 .name()
      );
      assertEquals(
          "updated description",
          updated.getValue()
                 .description()
      );
      assertEquals(
          Set.of("updated"),
          updated.getValue()
                 .tags()
      );
      assertFalse(Files.exists(uploadDir.resolve("old-storage-key.zip")));
      assertArchiveContains(uploadDir.resolve(response.getBody() + ".zip"), contents);
      verify(metadataService, never()).create(any());
    }

  }

}
