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
import io.repsy.protocols.npm.shared.npm_package.dtos.NpmPackageSnapshot.Version;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1300: a package whose metadata file is gone from storage is rebuilt from its rows before a
 * change is made to it, served from them when read, and the file is taken away again when the
 * change fails, as it was not there before.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNpmStorageService rebuilds lost metadata (RPS-1300)")
@SuppressWarnings("unchecked")
class AbstractNpmStorageServiceRebuildTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String REPO_NAME = "npm-repo";
  private static final String PACKAGE = "demo";
  private static final Path BASE_PATH = Path.of(PACKAGE);
  private static final String METADATA_FILE = "demo/package.json";
  private static final String STORED = "{\"name\":\"demo\",\"versions\":{}}";

  private static final NpmPackageSnapshot SNAPSHOT =
      new NpmPackageSnapshot(
          null,
          PACKAGE,
          "2.0.0",
          Instant.parse("2026-01-01T00:00:00Z"),
          List.of(version("1.0.0"), version("2.0.0")),
          Map.of("latest", "2.0.0"));

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock private StorageStrategy storageStrategy;

  private TestStorageService service;
  private final AtomicInteger snapshotReads = new AtomicInteger();
  private final Supplier<NpmPackageSnapshot> rows =
      () -> {
        this.snapshotReads.incrementAndGet();
        return SNAPSHOT;
      };

  static class TestStorageService extends AbstractNpmStorageService {

    @Nullable String registryBaseUrl;

    TestStorageService(final StorageStrategy storageStrategy) {
      super(storageStrategy);
    }

    @Override
    protected @Nullable String registryBaseUrl() {
      return this.registryBaseUrl;
    }
  }

  @BeforeEach
  void setUp() {
    this.service = new TestStorageService(this.storageStrategy);
  }

  private static Version version(final String version) {
    return new Version(
        version,
        Instant.parse("2026-02-02T00:00:00Z"),
        "version " + version,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        List.of());
  }

  /** Matches the storage path of the file, which has no equals of its own. */
  private static StoragePath at(final String file) {
    return argThat(path -> path != null && path.getPath().equals(REPO_ID + "/" + file));
  }

  private void metadataIsStored() {
    when(this.storageStrategy.get(at(METADATA_FILE), anyString()))
        .thenReturn(Optional.of(new ByteArrayResource(STORED.getBytes(StandardCharsets.UTF_8))));
  }

  private void metadataIsGone() {
    when(this.storageStrategy.get(at(METADATA_FILE), anyString())).thenReturn(Optional.empty());
  }

  private static byte[] tarGz(final String name, final String version) throws IOException {
    final var manifest =
        ("{\"name\":\""
                + name
                + "\",\"version\":\""
                + version
                + "\",\"dependencies\":{\"a\":\"1\"}}")
            .getBytes(StandardCharsets.UTF_8);
    final var out = new ByteArrayOutputStream();

    try (final var gzip = new GzipCompressorOutputStream(out);
        final var tar = new TarArchiveOutputStream(gzip)) {
      final var entry = new TarArchiveEntry("package/package.json");
      entry.setSize(manifest.length);
      tar.putArchiveEntry(entry);
      tar.write(manifest);
      tar.closeArchiveEntry();
      tar.finish();
    }

    return out.toByteArray();
  }

  private void tarballIsStored(final String version, final byte[] bytes) {
    when(this.storageStrategy.get(at("demo/demo-" + version + ".tgz"), anyString()))
        .thenReturn(Optional.of(new ByteArrayResource(bytes)));
  }

  private static Map<String, Object> parse(final byte[] bytes) {
    return MAPPER.readValue(bytes, new TypeReference<>() {});
  }

  private List<Map<String, Object>> writtenMetadata() throws IOException {
    final var streams = ArgumentCaptor.forClass(InputStream.class);
    verify(this.storageStrategy, org.mockito.Mockito.atLeastOnce())
        .write(eq(REPO_NAME), at(METADATA_FILE), streams.capture());

    return streams.getAllValues().stream()
        .map(
            stream -> {
              try {
                return parse(stream.readAllBytes());
              } catch (final IOException e) {
                throw new IllegalStateException(e);
              }
            })
        .toList();
  }

  // -------------------------------------------------------------------------------------------
  // changeMetadata
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a metadata file that is there is changed as it is, and the rows are not asked for")
  void changesTheStoredFile() throws Exception {
    this.metadataIsStored();

    final var growth =
        this.service.changeMetadata(REPO_ID, REPO_NAME, BASE_PATH, this.rows, () -> 11L);

    assertThat(growth).isEqualTo(11L);
    assertThat(this.snapshotReads).hasValue(0);
    verify(this.storageStrategy, never()).write(any(), any(), any());
  }

  @Test
  @DisplayName(
      "a metadata file that is gone is rebuilt from the rows first, and counts in the growth")
  void rebuildsTheMissingFileFirst() throws Exception {
    this.metadataIsGone();
    when(this.storageStrategy.write(eq(REPO_NAME), at(METADATA_FILE), any()))
        .thenReturn(BaseUsages.ofDisk(500L));
    final var changeSawTheFile = new AtomicInteger();

    final var growth =
        this.service.changeMetadata(
            REPO_ID,
            REPO_NAME,
            BASE_PATH,
            this.rows,
            () -> {
              verify(this.storageStrategy).write(eq(REPO_NAME), at(METADATA_FILE), any());
              changeSawTheFile.incrementAndGet();
              return -20L;
            });

    assertThat(growth).as("the rebuilt file, and what the change did to it").isEqualTo(480L);
    assertThat(changeSawTheFile).hasValue(1);
    final var rebuilt = this.writtenMetadata().getFirst();
    assertThat(rebuilt).containsEntry("name", "demo");
    assertThat(((Map<String, Object>) rebuilt.get("versions")).keySet())
        .containsExactly("1.0.0", "2.0.0");
    assertThat(rebuilt.get("dist-tags")).isEqualTo(Map.of("latest", "2.0.0"));
  }

  @Test
  @DisplayName("a change that fails takes the rebuilt file away again")
  void aFailedChangeRemovesTheRebuiltFile() throws Exception {
    // Gone when the change starts, there once it has been rebuilt.
    when(this.storageStrategy.get(at(METADATA_FILE), anyString()))
        .thenReturn(
            Optional.empty(),
            Optional.of(new ByteArrayResource(STORED.getBytes(StandardCharsets.UTF_8))));
    when(this.storageStrategy.write(eq(REPO_NAME), at(METADATA_FILE), any()))
        .thenReturn(BaseUsages.ofDisk(500L));
    final var failure = new IllegalStateException("storage refused");

    assertThatThrownBy(
            () ->
                this.service.changeMetadata(
                    REPO_ID,
                    REPO_NAME,
                    BASE_PATH,
                    this.rows,
                    () -> {
                      throw failure;
                    }))
        .isSameAs(failure);

    final var order = inOrder(this.storageStrategy);
    order.verify(this.storageStrategy).write(eq(REPO_NAME), at(METADATA_FILE), any());
    order.verify(this.storageStrategy).delete(at(METADATA_FILE));
  }

  @Test
  @DisplayName("a change that fails puts back a file that was there, and does not remove it")
  void aFailedChangeRestoresAStoredFile() throws Exception {
    this.metadataIsStored();
    final var failure = new IOException("disk full");

    assertThatThrownBy(
            () ->
                this.service.changeMetadata(
                    REPO_ID,
                    REPO_NAME,
                    BASE_PATH,
                    this.rows,
                    () -> {
                      throw failure;
                    }))
        .isSameAs(failure);

    verify(this.storageStrategy, never()).delete(any());
    assertThat(this.writtenMetadata())
        .containsExactly(parse(STORED.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  @DisplayName("a failed removal of the rebuilt file is attached to the change's failure")
  void aFailedRemovalIsAttached() throws Exception {
    when(this.storageStrategy.get(at(METADATA_FILE), anyString()))
        .thenReturn(
            Optional.empty(),
            Optional.of(new ByteArrayResource(STORED.getBytes(StandardCharsets.UTF_8))));
    when(this.storageStrategy.write(eq(REPO_NAME), at(METADATA_FILE), any()))
        .thenReturn(BaseUsages.ofDisk(1L));
    final var removalFailure = new IllegalStateException("disk gone");
    doThrow(removalFailure).when(this.storageStrategy).delete(at(METADATA_FILE));
    final var failure = new IllegalStateException("storage refused");

    assertThatThrownBy(
            () ->
                this.service.changeMetadata(
                    REPO_ID,
                    REPO_NAME,
                    BASE_PATH,
                    this.rows,
                    () -> {
                      throw failure;
                    }))
        .isSameAs(failure)
        .hasSuppressedException(removalFailure);
  }

  @Test
  @DisplayName("a rebuilt file that cannot be written fails the change before it starts")
  void aFailedRebuildWriteStopsTheChange() {
    this.metadataIsGone();
    final var failure = new IllegalStateException("disk full");
    when(this.storageStrategy.write(eq(REPO_NAME), at(METADATA_FILE), any())).thenThrow(failure);
    final var changed = new AtomicInteger();

    assertThatThrownBy(
            () ->
                this.service.changeMetadata(
                    REPO_ID,
                    REPO_NAME,
                    BASE_PATH,
                    this.rows,
                    () -> {
                      changed.incrementAndGet();
                      return 0L;
                    }))
        .isSameAs(failure);

    assertThat(changed).hasValue(0);
    verify(this.storageStrategy, never()).delete(any());
  }

  @Test
  @DisplayName("a package the rows do not know either is a not-found, and nothing is written")
  void noRowsNoRebuild() {
    this.metadataIsGone();

    assertThatThrownBy(
            () ->
                this.service.changeMetadata(
                    REPO_ID,
                    REPO_NAME,
                    BASE_PATH,
                    () -> {
                      throw new ItemNotFoundException("packageNotFound");
                    },
                    () -> 0L))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("packageNotFound");

    verify(this.storageStrategy, never()).write(any(), any(), any());
  }

  @Test
  @DisplayName("removing a version whose metadata is gone rebuilds it, then removes the version")
  void removeVersionRebuildsFirst() throws Exception {
    when(this.storageStrategy.get(at("demo/demo-2.0.0.tgz"), anyString()))
        .thenReturn(Optional.empty());
    final var afterRebuild =
        new ByteArrayResource(
            ("{\"name\":\"demo\",\"dist-tags\":{\"latest\":\"2.0.0\"},"
                    + "\"time\":{\"1.0.0\":\"a\",\"2.0.0\":\"b\"},"
                    + "\"versions\":{\"1.0.0\":{},\"2.0.0\":{}}}")
                .getBytes(StandardCharsets.UTF_8));
    when(this.storageStrategy.get(at(METADATA_FILE), anyString()))
        .thenReturn(Optional.empty(), Optional.of(afterRebuild), Optional.of(afterRebuild));
    when(this.storageStrategy.write(eq(REPO_NAME), at(METADATA_FILE), any()))
        .thenReturn(BaseUsages.ofDisk(300L), BaseUsages.ofDisk(-40L));
    when(this.storageStrategy.getFileUsage(at("demo/demo-1.0.0.tgz"), eq(REPO_NAME)))
        .thenReturn(60L);
    tarballIsStored("1.0.0", new byte[60]);

    final var growth =
        this.service.removeVersion(
            REPO_ID, REPO_NAME, BASE_PATH, PACKAGE, "1.0.0", null, this.rows);

    assertThat(growth).as("rebuilt 300, then -40 for the metadata and 60 freed").isEqualTo(200L);
    final var writes = this.writtenMetadata();
    assertThat(((Map<String, Object>) writes.getLast().get("versions")).keySet())
        .containsExactly("2.0.0");
    final var order = inOrder(this.storageStrategy);
    order.verify(this.storageStrategy, times(2)).write(eq(REPO_NAME), at(METADATA_FILE), any());
    order.verify(this.storageStrategy).delete(at("demo/demo-1.0.0.tgz"));
  }

  // -------------------------------------------------------------------------------------------
  // What the rebuild reads from the tarballs
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("the rebuilt versions take dependencies and digests from their tarballs")
  void rebuiltVersionsReadTheirTarballs() throws Exception {
    final var tarball = tarGz("demo", "1.0.0");
    this.metadataIsGone();
    tarballIsStored("1.0.0", tarball);
    when(this.storageStrategy.get(at("demo/demo-2.0.0.tgz"), anyString()))
        .thenReturn(Optional.empty());

    final var rebuilt =
        this.service.readMetadataOrRebuild(REPO_ID, REPO_NAME, BASE_PATH, this.rows);

    final var versions = (Map<String, Map<String, Object>>) rebuilt.get("versions");
    assertThat(versions.get("1.0.0"))
        .containsEntry("dependencies", Map.of("a", "1"))
        .containsEntry("description", "version 1.0.0");
    assertThat((Map<String, Object>) versions.get("1.0.0").get("dist"))
        .containsEntry("shasum", DigestUtils.sha1Hex(tarball))
        .doesNotContainKey("tarball");
    assertThat(versions.get("2.0.0")).doesNotContainKeys("dist", "dependencies");
  }

  @Test
  @DisplayName("the tarball URL is the registry address, the repo and the package")
  void rebuiltVersionsGetTheTarballUrl() throws Exception {
    this.metadataIsGone();
    this.service.registryBaseUrl = "https://registry.example.test:9090/";

    final var rebuilt =
        this.service.readMetadataOrRebuild(REPO_ID, REPO_NAME, BASE_PATH, this.rows);

    final var versions = (Map<String, Map<String, Object>>) rebuilt.get("versions");
    assertThat((Map<String, Object>) versions.get("2.0.0").get("dist"))
        .containsEntry(
            "tarball", "https://registry.example.test:9090/npm-repo/demo/-/demo-2.0.0.tgz");
  }

  @Test
  @DisplayName("a tarball that cannot be read fails the rebuild instead of leaving it half-done")
  void unreadableTarballFailsTheRebuild() throws Exception {
    this.metadataIsGone();
    final var broken =
        new ByteArrayResource(new byte[0]) {
          @Override
          public InputStream getInputStream() throws IOException {
            throw new IOException("disk gone");
          }
        };
    when(this.storageStrategy.get(at("demo/demo-1.0.0.tgz"), anyString()))
        .thenReturn(Optional.of(broken));

    assertThatThrownBy(
            () -> this.service.readMetadataOrRebuild(REPO_ID, REPO_NAME, BASE_PATH, this.rows))
        .isInstanceOf(IOException.class)
        .hasMessage("disk gone");
  }

  // -------------------------------------------------------------------------------------------
  // Reading
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("reading serves the stored file when it is there, without asking for the rows")
  void readsTheStoredFile() throws Exception {
    this.metadataIsStored();

    final var metadata =
        this.service.readMetadataOrRebuild(REPO_ID, REPO_NAME, BASE_PATH, this.rows);

    assertThat(metadata).containsEntry("name", "demo");
    assertThat(this.snapshotReads).hasValue(0);
  }

  @Test
  @DisplayName("reading serves the rows when the file is gone, and writes nothing")
  void readsTheRowsWhenTheFileIsGone() throws Exception {
    this.metadataIsGone();

    final var metadata =
        this.service.readMetadataOrRebuild(REPO_ID, REPO_NAME, BASE_PATH, this.rows);

    assertThat(((Map<String, Object>) metadata.get("versions")).keySet())
        .containsExactly("1.0.0", "2.0.0");
    verify(this.storageStrategy, never()).write(any(), any(), any());
  }

  @Test
  @DisplayName("the packument read serves the rows of a package whose file is gone")
  void packumentReadServesTheRows() throws Exception {
    this.metadataIsGone();

    final var full = this.service.getMetadata(REPO_ID, REPO_NAME, null, PACKAGE, false, this.rows);
    final var abbreviated =
        this.service.getMetadata(REPO_ID, REPO_NAME, null, PACKAGE, true, this.rows);

    assertThat(((Map<String, Object>) full.get("versions")).keySet())
        .containsExactly("1.0.0", "2.0.0");
    assertThat(full).containsKey("time");
    assertThat(((Map<String, Object>) abbreviated.get("versions")).keySet())
        .containsExactly("1.0.0", "2.0.0");
    assertThat(abbreviated).containsKey("modified");
    assertThat(abbreviated).doesNotContainKey("time");
    verify(this.storageStrategy, never()).write(any(), any(), any());
  }

  @Test
  @DisplayName("the packument read of a package neither the file nor the rows know fails as before")
  void packumentReadOfAnUnknownPackage() {
    this.metadataIsGone();

    assertThatThrownBy(
            () ->
                this.service.getMetadata(
                    REPO_ID,
                    REPO_NAME,
                    null,
                    PACKAGE,
                    false,
                    () -> {
                      throw new ItemNotFoundException("packageNotFound");
                    }))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("itemNotFound");
  }

  @Test
  @DisplayName("the packument read serves the stored file when it is there")
  void packumentReadServesTheStoredFile() throws Exception {
    this.metadataIsStored();

    final var metadata =
        this.service.getMetadata(REPO_ID, REPO_NAME, null, PACKAGE, false, this.rows);

    assertThat(metadata).containsEntry("name", "demo");
    assertThat(this.snapshotReads).hasValue(0);
  }

  // -------------------------------------------------------------------------------------------
  // Restoring
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("restoring to nothing removes the file that is there")
  void restoringToNothingRemovesTheFile() throws Exception {
    this.metadataIsStored();

    this.service.restoreMetadataBytes(REPO_ID, REPO_NAME, BASE_PATH, null);

    verify(this.storageStrategy).delete(at(METADATA_FILE));
  }

  @Test
  @DisplayName("restoring to nothing leaves alone a file that is not there")
  void restoringToNothingToleratesAMissingFile() throws Exception {
    this.metadataIsGone();

    this.service.restoreMetadataBytes(REPO_ID, REPO_NAME, BASE_PATH, null);

    verify(this.storageStrategy, never()).delete(any());
  }
}
