/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.repsy.protocols.npm.shared.storage.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;

/**
 * RPS-1143: {@link AbstractNpmStorageService#getReadmeContent} used to throw a {@code
 * NullPointerException} (surfaced to the panel as a 500) whenever {@code metadata.json} had no
 * {@code versions} map, no entry for the requested version, or a non-string {@code readme} field. A
 * database/storage mismatch for one version -- for example after a partial publish or a manual
 * storage edit -- made the whole version detail page unusable, although every other field comes
 * from the database and could still be shown. It now returns {@code null} in those three cases so
 * the page renders without a README.
 *
 * <p>A completely missing {@code metadata.json} is a different failure mode: it means storage
 * itself is broken for this package, so it is left to surface as {@link ItemNotFoundException}
 * rather than being swallowed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNpmStorageService.getReadmeContent (RPS-1143)")
class AbstractNpmStorageServiceTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String REPO_NAME = "npm-repo";
  private static final Path PACKAGE_BASE_PATH = Path.of("my-package");
  private static final String VERSION_NAME = "1.0.0";

  @Mock private StorageStrategy storageStrategy;

  private AbstractNpmStorageService service;

  @BeforeEach
  void setUp() {
    this.service = new TestStorageService(this.storageStrategy);
  }

  static class TestStorageService extends AbstractNpmStorageService {
    TestStorageService(final StorageStrategy storageStrategy) {
      super(storageStrategy);
    }
  }

  private void stubMetadata(final String json) {
    when(this.storageStrategy.get(any(StoragePath.class), anyString()))
        .thenReturn(Optional.of(new ByteArrayResource(json.getBytes())));
  }

  @Nested
  @DisplayName("when the version entry is incomplete")
  class IncompleteVersionEntry {

    @Test
    @DisplayName("returns null when metadata.json has no versions map at all")
    void returnsNullWhenVersionsMapIsMissing() throws Exception {
      stubMetadata("{\"name\":\"my-package\"}");

      final var readme =
          service.getReadmeContent(REPO_ID, REPO_NAME, PACKAGE_BASE_PATH, VERSION_NAME);

      assertThat(readme).isNull();
    }

    @Test
    @DisplayName("returns null when versions has no entry for the requested version")
    void returnsNullWhenVersionEntryIsMissing() throws Exception {
      stubMetadata(
          "{\"name\":\"my-package\",\"versions\":{\"2.0.0\":{\"readme\":\"other version\"}}}");

      final var readme =
          service.getReadmeContent(REPO_ID, REPO_NAME, PACKAGE_BASE_PATH, VERSION_NAME);

      assertThat(readme).isNull();
    }

    @Test
    @DisplayName("returns null when the version entry has no readme field")
    void returnsNullWhenReadmeFieldIsMissing() throws Exception {
      stubMetadata(
          "{\"name\":\"my-package\",\"versions\":{\""
              + VERSION_NAME
              + "\":{\"version\":\"1.0.0\"}}}");

      final var readme =
          service.getReadmeContent(REPO_ID, REPO_NAME, PACKAGE_BASE_PATH, VERSION_NAME);

      assertThat(readme).isNull();
    }

    @Test
    @DisplayName("returns null rather than a cast exception when readme is not a string")
    void returnsNullWhenReadmeIsNotAString() throws Exception {
      stubMetadata(
          "{\"name\":\"my-package\",\"versions\":{\""
              + VERSION_NAME
              + "\":{\"readme\":{\"nested\":\"object\"}}}}");

      final var readme =
          service.getReadmeContent(REPO_ID, REPO_NAME, PACKAGE_BASE_PATH, VERSION_NAME);

      assertThat(readme).isNull();
    }
  }

  @Nested
  @DisplayName("when the version entry is complete")
  class CompleteVersionEntry {

    @Test
    @DisplayName("returns the readme string for the requested version")
    void returnsTheReadmeContent() throws Exception {
      stubMetadata(
          "{\"name\":\"my-package\",\"versions\":{\""
              + VERSION_NAME
              + "\":{\"readme\":\"# Hello\"}}}");

      final var readme =
          service.getReadmeContent(REPO_ID, REPO_NAME, PACKAGE_BASE_PATH, VERSION_NAME);

      assertThat(readme).isEqualTo("# Hello");
    }
  }

  @Nested
  @DisplayName("when metadata.json itself is missing")
  class MissingMetadataFile {

    @Test
    @DisplayName(
        "still throws ItemNotFoundException instead of being swallowed -- storage is broken")
    void stillThrowsInsteadOfReturningNull() {
      when(storageStrategy.get(any(StoragePath.class), anyString())).thenReturn(Optional.empty());

      assertThatThrownBy(
              () -> service.getReadmeContent(REPO_ID, REPO_NAME, PACKAGE_BASE_PATH, VERSION_NAME))
          .isInstanceOf(ItemNotFoundException.class);
    }
  }

  @Nested
  @DisplayName("RPS-1124: undoing a publish that failed part-way")
  class DiscardPublishedVersion {

    private static final String TARBALL = "my-package/my-package-1.0.0.tgz";
    private static final String METADATA = "my-package/package.json";

    private StoragePath pathEnding(final String path) {
      return org.mockito.ArgumentMatchers.argThat(
          (StoragePath p) -> p != null && p.getPath().endsWith(path));
    }

    private void stubPresent(final String path, final boolean present) {
      when(storageStrategy.get(
              org.mockito.ArgumentMatchers.argThat(
                  (StoragePath p) -> p != null && p.getPath().endsWith(path)),
              anyString()))
          .thenReturn(present ? Optional.of(new ByteArrayResource(new byte[0])) : Optional.empty());
    }

    @Test
    @DisplayName("reads the stored metadata as it is")
    void readsTheMetadataBytes() throws Exception {
      stubMetadata("{\"name\":\"my-package\"}");

      assertThat(service.readMetadataBytes(REPO_ID, REPO_NAME, PACKAGE_BASE_PATH))
          .isEqualTo("{\"name\":\"my-package\"}".getBytes());
    }

    @Test
    @DisplayName("a package without metadata cannot be read")
    void missingMetadataCannotBeRead() {
      when(storageStrategy.get(any(StoragePath.class), anyString())).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.readMetadataBytes(REPO_ID, REPO_NAME, PACKAGE_BASE_PATH))
          .isInstanceOf(ItemNotFoundException.class);
    }

    @Test
    @DisplayName("removes the tarball and the metadata of a package the publish was creating")
    void removesTheFilesOfANewPackage() throws Exception {
      stubPresent(TARBALL, true);
      stubPresent(METADATA, true);

      service.discardPublishedVersion(
          REPO_ID, REPO_NAME, PACKAGE_BASE_PATH, "my-package", VERSION_NAME, null);

      verify(storageStrategy).delete(pathEnding(TARBALL));
      verify(storageStrategy).delete(pathEnding(METADATA));
      verify(storageStrategy, never()).write(anyString(), any(StoragePath.class), any());
    }

    @Test
    @DisplayName("skips the files the publish never got to write")
    void skipsFilesThatWereNeverWritten() throws Exception {
      stubPresent(TARBALL, false);
      stubPresent(METADATA, false);

      service.discardPublishedVersion(
          REPO_ID, REPO_NAME, PACKAGE_BASE_PATH, "my-package", VERSION_NAME, null);

      verify(storageStrategy, never()).delete(any(StoragePath.class));
    }

    @Test
    @DisplayName("removes the tarball of a new version and puts the previous metadata back")
    void restoresThePreviousMetadata() throws Exception {
      final var previous = "{\"name\":\"my-package\"}".getBytes();
      final var written = new java.util.concurrent.atomic.AtomicReference<byte[]>();
      stubPresent(TARBALL, true);
      when(storageStrategy.write(anyString(), any(StoragePath.class), any()))
          .thenAnswer(
              invocation -> {
                written.set(invocation.<java.io.InputStream>getArgument(2).readAllBytes());
                return BaseUsages.ofDisk(previous.length);
              });

      service.discardPublishedVersion(
          REPO_ID, REPO_NAME, PACKAGE_BASE_PATH, "my-package", VERSION_NAME, previous);

      verify(storageStrategy).delete(pathEnding(TARBALL));
      verify(storageStrategy, never()).delete(pathEnding(METADATA));
      verify(storageStrategy).write(eq(REPO_NAME), pathEnding(METADATA), any());
      assertThat(written.get()).isEqualTo(previous);
    }

    @Test
    @DisplayName("puts the given metadata bytes back without touching the tarball")
    void restoresMetadataBytes() throws Exception {
      final var previous = "{\"name\":\"my-package\"}".getBytes();
      final var written = new java.util.concurrent.atomic.AtomicReference<byte[]>();
      when(storageStrategy.write(anyString(), any(StoragePath.class), any()))
          .thenAnswer(
              invocation -> {
                written.set(invocation.<java.io.InputStream>getArgument(2).readAllBytes());
                return BaseUsages.ofDisk(0L);
              });

      service.restoreMetadataBytes(REPO_ID, REPO_NAME, PACKAGE_BASE_PATH, previous);

      verify(storageStrategy).write(eq(REPO_NAME), pathEnding(METADATA), any());
      verify(storageStrategy, never()).delete(any(StoragePath.class));
      assertThat(written.get()).isEqualTo(previous);
    }

    @Test
    @DisplayName("tells whether the tarball of a version is stored")
    void tellsWhetherTheTarballExists() {
      stubPresent(TARBALL, true);
      assertThat(
              service.tarballExists(
                  REPO_ID, REPO_NAME, PACKAGE_BASE_PATH, "my-package", VERSION_NAME))
          .isTrue();

      stubPresent(TARBALL, false);
      assertThat(
              service.tarballExists(
                  REPO_ID, REPO_NAME, PACKAGE_BASE_PATH, "my-package", VERSION_NAME))
          .isFalse();
    }
  }
}
