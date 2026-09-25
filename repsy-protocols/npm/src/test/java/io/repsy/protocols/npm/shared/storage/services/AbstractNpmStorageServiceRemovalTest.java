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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.protocols.npm.shared.npm_package.dtos.NpmPackageSnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.util.Pair;

/**
 * RPS-1280: {@link AbstractNpmStorageService#removeVersion} rewrites the package metadata before it
 * removes the tarball, restores the metadata when anything before the tarball's removal fails, and
 * {@link AbstractNpmStorageService#deprecateVersions} changes the metadata as it is stored.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNpmStorageService removal and deprecation (RPS-1280)")
class AbstractNpmStorageServiceRemovalTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String REPO_NAME = "npm-repo";
  private static final String PACKAGE = "demo";
  private static final Path BASE_PATH = Path.of(PACKAGE);
  private static final String METADATA_FILE = "demo/package.json";
  private static final String TARBALL_FILE = "demo/demo-1.2.0.tgz";

  private static final String METADATA =
      """
      {"name":"demo","description":"three",
       "dist-tags":{"latest":"1.2.0","beta":"1.2.0","old":"1.0.0"},
       "time":{"modified":"then","1.0.0":"a","1.1.0":"b","1.2.0":"c"},
       "versions":{
         "1.0.0":{"name":"demo","version":"1.0.0","description":"one"},
         "1.1.0":{"name":"demo","version":"1.1.0","description":"two","readme":"r2"},
         "1.2.0":{"name":"demo","version":"1.2.0","description":"three"}}}
      """;

  /** For a package whose metadata is stored: the rows are never asked for. */
  private static final Supplier<NpmPackageSnapshot> NO_ROWS =
      () -> {
        throw new AssertionError("the metadata is stored, so the rows are not needed");
      };

  /** For a package the database does not know either. */
  private static final Supplier<NpmPackageSnapshot> NO_PACKAGE =
      () -> {
        throw new ItemNotFoundException("packageNotFound");
      };

  @Mock private StorageStrategy storageStrategy;

  private AbstractNpmStorageService service;

  static class TestStorageService extends AbstractNpmStorageService {
    TestStorageService(final StorageStrategy storageStrategy) {
      super(storageStrategy);
    }
  }

  @BeforeEach
  void setUp() {
    this.service = new TestStorageService(this.storageStrategy);
  }

  /** Matches the storage path of the file, which has no equals of its own. */
  private static StoragePath at(final String file) {
    return argThat(path -> path != null && path.getPath().equals(REPO_ID + "/" + file));
  }

  private void metadataIsStored() {
    when(this.storageStrategy.get(at(METADATA_FILE), anyString()))
        .thenReturn(Optional.of(new ByteArrayResource(METADATA.getBytes(StandardCharsets.UTF_8))));
  }

  private void tarballIsStored(final int size) {
    when(this.storageStrategy.get(at(TARBALL_FILE), anyString()))
        .thenReturn(Optional.of(new ByteArrayResource(new byte[size])));
  }

  private String written() throws IOException {
    final var stream = ArgumentCaptor.forClass(InputStream.class);
    verify(this.storageStrategy).write(eq(REPO_NAME), at(METADATA_FILE), stream.capture());

    return new String(stream.getValue().readAllBytes(), StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("removing the latest version moves latest and drops the tags pointing at it")
  void removingTheLatestMovesLatest() throws Exception {
    this.metadataIsStored();
    this.tarballIsStored(40);
    when(this.storageStrategy.getFileUsage(at(TARBALL_FILE), eq(REPO_NAME))).thenReturn(40L);
    when(this.storageStrategy.write(eq(REPO_NAME), at(METADATA_FILE), any()))
        .thenReturn(BaseUsages.ofDisk(-100L));

    final var growth =
        this.service.removeVersion(
            REPO_ID, REPO_NAME, BASE_PATH, PACKAGE, "1.2.0", "1.1.0", NO_ROWS);

    assertThat(growth).as("the metadata shrank by 100, the tarball freed 40").isEqualTo(-140L);
    final var written = this.written();
    assertThat(written).doesNotContain("\"1.2.0\"");
    assertThat(written).contains("\"latest\":\"1.1.0\"").contains("\"old\":\"1.0.0\"");
    assertThat(written).doesNotContain("beta");
    assertThat(written).contains("\"description\":\"two\"").contains("\"readme\":\"r2\"");
    verify(this.storageStrategy).delete(at(TARBALL_FILE));
  }

  @Test
  @DisplayName("removing an older version leaves latest and the other tags alone")
  void removingAnOlderVersionKeepsLatest() throws Exception {
    this.metadataIsStored();
    when(this.storageStrategy.get(at("demo/demo-1.0.0.tgz"), anyString()))
        .thenReturn(Optional.empty());
    when(this.storageStrategy.write(eq(REPO_NAME), at(METADATA_FILE), any()))
        .thenReturn(BaseUsages.ofDisk(-10L));

    final var growth =
        this.service.removeVersion(REPO_ID, REPO_NAME, BASE_PATH, PACKAGE, "1.0.0", null, NO_ROWS);

    assertThat(growth).as("a tarball that is gone frees nothing").isEqualTo(-10L);
    final var written = this.written();
    assertThat(written).contains("\"latest\":\"1.2.0\"").contains("\"beta\":\"1.2.0\"");
    assertThat(written).doesNotContain("\"old\"").doesNotContain("\"1.0.0\"");
    verify(this.storageStrategy, never()).delete(any());
  }

  @Test
  @DisplayName("the tarball goes after the metadata was written")
  void theTarballIsRemovedLast() throws Exception {
    this.metadataIsStored();
    this.tarballIsStored(5);
    when(this.storageStrategy.write(eq(REPO_NAME), at(METADATA_FILE), any()))
        .thenReturn(BaseUsages.ofDisk(0L));

    this.service.removeVersion(REPO_ID, REPO_NAME, BASE_PATH, PACKAGE, "1.2.0", "1.1.0", NO_ROWS);

    final var order = inOrder(this.storageStrategy);
    order.verify(this.storageStrategy).write(eq(REPO_NAME), at(METADATA_FILE), any());
    order.verify(this.storageStrategy).delete(at(TARBALL_FILE));
  }

  @Test
  @DisplayName("a tarball that cannot be removed puts the metadata back as it was read")
  void aFailedTarballRemovalRestoresTheMetadata() throws Exception {
    this.metadataIsStored();
    this.tarballIsStored(5);
    when(this.storageStrategy.write(eq(REPO_NAME), at(METADATA_FILE), any()))
        .thenReturn(BaseUsages.ofDisk(0L));
    final var failure = new IllegalStateException("storage refused");
    doThrow(failure).when(this.storageStrategy).delete(at(TARBALL_FILE));

    assertThatThrownBy(
            () ->
                this.service.removeVersion(
                    REPO_ID, REPO_NAME, BASE_PATH, PACKAGE, "1.2.0", "1.1.0", NO_ROWS))
        .isSameAs(failure);

    final var restored = ArgumentCaptor.forClass(InputStream.class);
    verify(this.storageStrategy, times(2))
        .write(eq(REPO_NAME), at(METADATA_FILE), restored.capture());
    final var lastWrite = restored.getAllValues().get(1);
    assertThat(new String(lastWrite.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(METADATA);
  }

  @Test
  @DisplayName("a metadata write that fails is restored, and the tarball is never touched")
  void aFailedMetadataWriteLeavesTheTarball() throws Exception {
    this.metadataIsStored();
    when(this.storageStrategy.write(eq(REPO_NAME), at(METADATA_FILE), any()))
        .thenThrow(new IllegalStateException("disk full"))
        .thenReturn(BaseUsages.ofDisk(0L));

    assertThatThrownBy(
            () ->
                this.service.removeVersion(
                    REPO_ID, REPO_NAME, BASE_PATH, PACKAGE, "1.2.0", "1.1.0", NO_ROWS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("disk full");

    verify(this.storageStrategy, never()).delete(any());
    verify(this.storageStrategy, times(2)).write(eq(REPO_NAME), at(METADATA_FILE), any());
  }

  @Test
  @DisplayName("the removal's failure is reported, with a failed restore attached to it")
  void aFailedRestoreIsAttachedToTheFailure() throws Exception {
    this.metadataIsStored();
    this.tarballIsStored(5);
    final var restoreFailure = new IllegalStateException("disk gone");
    when(this.storageStrategy.write(eq(REPO_NAME), at(METADATA_FILE), any()))
        .thenReturn(BaseUsages.ofDisk(0L))
        .thenThrow(restoreFailure);
    final var failure = new IllegalStateException("storage refused");
    doThrow(failure).when(this.storageStrategy).delete(at(TARBALL_FILE));

    assertThatThrownBy(
            () ->
                this.service.removeVersion(
                    REPO_ID, REPO_NAME, BASE_PATH, PACKAGE, "1.2.0", "1.1.0", NO_ROWS))
        .isSameAs(failure)
        .hasSuppressedException(restoreFailure);
  }

  @Test
  @DisplayName("a package without metadata and without rows is a not-found, before anything goes")
  void aMissingMetadataFileIsNotFound() {
    when(this.storageStrategy.get(at(METADATA_FILE), anyString())).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                this.service.removeVersion(
                    REPO_ID, REPO_NAME, BASE_PATH, PACKAGE, "1.2.0", "1.1.0", NO_PACKAGE))
        .isInstanceOf(ItemNotFoundException.class);

    verify(this.storageStrategy, never()).delete(any());
  }

  @Test
  @DisplayName(
      "deprecating sets the message on the stored metadata, an empty one removes it (RPS-1360)")
  void deprecatingPatchesTheStoredMetadata() throws Exception {
    this.metadataIsStored();
    when(this.storageStrategy.write(eq(REPO_NAME), at(METADATA_FILE), any()))
        .thenReturn(BaseUsages.ofDisk(23L));

    final var growth =
        this.service.deprecateVersions(
            REPO_ID,
            REPO_NAME,
            BASE_PATH,
            List.of(Pair.of("1.0.0", "old"), Pair.of("1.1.0", ""), Pair.of("9.9.9", "gone")));

    assertThat(growth).isEqualTo(23L);
    final var written = this.written();
    assertThat(written).contains("\"deprecated\":\"old\"").doesNotContain("\"deprecated\":\"\"");
    assertThat(written).contains("\"1.2.0\"").doesNotContain("9.9.9");
    assertThat(written).doesNotContain("\"modified\":\"then\"");
  }

  @Test
  @DisplayName("the restore writes the bytes that were read")
  void restoreUsesTheGivenBytes() throws Exception {
    this.service.restoreMetadataBytes(
        REPO_ID, REPO_NAME, BASE_PATH, "x".getBytes(StandardCharsets.UTF_8));

    final var stream = ArgumentCaptor.forClass(InputStream.class);
    verify(this.storageStrategy).write(eq(REPO_NAME), at(METADATA_FILE), stream.capture());
    assertThat(stream.getValue().readAllBytes()).isEqualTo("x".getBytes(StandardCharsets.UTF_8));
  }
}
