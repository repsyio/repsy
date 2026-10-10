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
package io.repsy.protocols.helm.shared.storage.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ErrorOccurredException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StaleFile;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.protocols.shared.storage.RepoRef;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractHelmStorageService")
class AbstractHelmStorageServiceTest {

  private static final UUID REPO_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID UPLOAD_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final String REPO_NAME = "charts";
  private static final String UPLOAD_PATH = REPO_UUID + "/oci/blobs/" + UPLOAD_ID;

  @Mock private StorageStrategy storageStrategy;

  private static class TestStorageService extends AbstractHelmStorageService<UUID> {

    TestStorageService(final StorageStrategy storageStrategy) {
      super(storageStrategy);
    }
  }

  @Test
  @DisplayName("saveBlobChunk() appends the chunk and answers the usage it added to the upload")
  void saveBlobChunkAnswersTheWrittenUsage() {
    final var expected = BaseUsages.ofDisk(2048);
    when(this.storageStrategy.appendStream(
            eq(REPO_NAME),
            argThat(path -> path != null && path.getPath().equals(UPLOAD_PATH)),
            any()))
        .thenReturn(expected);

    final var usages =
        new TestStorageService(this.storageStrategy)
            .saveBlobChunk(
                new RepoRef(REPO_UUID, REPO_NAME),
                UPLOAD_ID,
                new ByteArrayInputStream(new byte[2048]));

    assertThat(usages).isSameAs(expected);
  }

  @Test
  @DisplayName("finalizeBlob() answers the usage freed when the digest was already stored")
  void finalizeBlobAnswersTheRenameUsage() {
    final var expected = BaseUsages.ofDisk(-2048);
    when(this.storageStrategy.renameObject(
            argThat(path -> path != null && path.getPath().equals(UPLOAD_PATH)), eq("sha256:abc")))
        .thenReturn(expected);

    final var usages =
        new TestStorageService(this.storageStrategy)
            .finalizeBlob(REPO_UUID, UPLOAD_ID, "sha256:abc");

    assertThat(usages).isSameAs(expected);
  }

  @Test
  @DisplayName("listStaleBlobFiles() asks the storage strategy for the repo's oci/blobs directory")
  void listStaleBlobFilesListsTheBlobsDirectory() {
    final var threshold = Instant.parse("2026-01-01T00:00:00Z");
    final var expected = List.of(new StaleFile(UPLOAD_ID.toString(), 10L));
    when(this.storageStrategy.listStaleFiles(
            argThat(path -> path != null && path.getPath().equals(REPO_UUID + "/oci/blobs")),
            eq(threshold)))
        .thenReturn(expected);

    final var files =
        new TestStorageService(this.storageStrategy).listStaleBlobFiles(REPO_UUID, threshold);

    assertThat(files).isSameAs(expected);
  }

  @Test
  @DisplayName("deleteBlobFile() answers the bytes the file held and deletes it")
  void deleteBlobFileAnswersTheFileUsage() throws IOException {
    when(this.storageStrategy.getFileUsage(
            argThat(path -> path != null && path.getPath().equals(UPLOAD_PATH)), eq(REPO_NAME)))
        .thenReturn(4096L);

    final var freed =
        new TestStorageService(this.storageStrategy)
            .deleteBlobFile(new RepoRef(REPO_UUID, REPO_NAME), UPLOAD_ID.toString());

    assertThat(freed).isEqualTo(4096L);
    final var order = inOrder(this.storageStrategy);
    order
        .verify(this.storageStrategy)
        .getFileUsage(argThat(sp -> sp.getPath().equals(UPLOAD_PATH)), eq(REPO_NAME));
    order.verify(this.storageStrategy).delete(argThat(sp -> sp.getPath().equals(UPLOAD_PATH)));
  }

  @Test
  @DisplayName("saveManifest() answers the usage the manifest file changed")
  void saveManifestAnswersTheWrittenUsage() {
    final var expected = BaseUsages.ofDisk(512);
    when(this.storageStrategy.write(
            eq(REPO_NAME),
            argThat(
                path ->
                    path != null
                        && path.getPath().equals(REPO_UUID + "/oci/manifests/payments/1.0.0")),
            any()))
        .thenReturn(expected);

    final var usages =
        new TestStorageService(this.storageStrategy)
            .saveManifest(
                new RepoRef(REPO_UUID, REPO_NAME),
                "payments",
                "1.0.0",
                "{}".getBytes(StandardCharsets.UTF_8));

    assertThat(usages).isSameAs(expected);
  }

  @Test
  @DisplayName("deleteManifestFile() answers the bytes the file held and deletes it")
  void deleteManifestFileAnswersTheFileUsage() throws IOException {
    final var path = REPO_UUID + "/oci/manifests/payments/1.0.0";
    when(this.storageStrategy.getFileUsage(
            argThat(sp -> sp != null && sp.getPath().equals(path)), eq(REPO_NAME)))
        .thenReturn(700L);

    final var freed =
        new TestStorageService(this.storageStrategy)
            .deleteManifestFile(new RepoRef(REPO_UUID, REPO_NAME), "payments", "1.0.0");

    assertThat(freed).isEqualTo(700L);
    verify(this.storageStrategy).delete(argThat(sp -> sp.getPath().equals(path)));
  }

  @Test
  @DisplayName("deleteManifestFile() answers zero and deletes nothing when there is no file")
  void deleteManifestFileWithoutFileFreesNothing() throws IOException {
    when(this.storageStrategy.getFileUsage(any(), eq(REPO_NAME))).thenReturn(0L);

    final var freed =
        new TestStorageService(this.storageStrategy)
            .deleteManifestFile(new RepoRef(REPO_UUID, REPO_NAME), "payments", "1.0.0");

    assertThat(freed).isZero();
    verify(this.storageStrategy, never()).delete(any());
  }

  @Test
  @DisplayName("deleteBlob() answers the bytes the blob held and deletes it")
  void deleteBlobAnswersTheBlobSize() throws IOException {
    final var path = REPO_UUID + "/oci/blobs/sha256:abc";
    when(this.storageStrategy.get(
            argThat(sp -> sp != null && sp.getPath().equals(path)), eq(REPO_NAME)))
        .thenReturn(Optional.of(new ByteArrayResource(new byte[64])));

    final var freed =
        new TestStorageService(this.storageStrategy)
            .deleteBlob(new RepoRef(REPO_UUID, REPO_NAME), "sha256:abc");

    assertThat(freed).isEqualTo(64L);
    verify(this.storageStrategy).delete(argThat(sp -> sp.getPath().equals(path)));
  }

  @Test
  @DisplayName("deleteBlob() answers zero and deletes nothing when the blob is not stored")
  void deleteBlobWithoutFileFreesNothing() throws IOException {
    when(this.storageStrategy.get(any(), eq(REPO_NAME))).thenReturn(Optional.empty());

    final var freed =
        new TestStorageService(this.storageStrategy)
            .deleteBlob(new RepoRef(REPO_UUID, REPO_NAME), "sha256:abc");

    assertThat(freed).isZero();
    verify(this.storageStrategy, never()).delete(any());
  }

  @Test
  @DisplayName(
      "deleteBlobFile() answers the IOException of the size lookup as errorOccurred (500), deleting nothing")
  void deleteBlobFileAnswersTheIoExceptionAsErrorOccurred() throws IOException {
    when(this.storageStrategy.getFileUsage(any(), eq(REPO_NAME)))
        .thenThrow(new IOException("disk"));
    final var service = new TestStorageService(this.storageStrategy);

    assertThatThrownBy(() -> service.deleteBlobFile(new RepoRef(REPO_UUID, REPO_NAME), "upload"))
        .isInstanceOf(ErrorOccurredException.class)
        .hasCauseInstanceOf(IOException.class);
    verify(this.storageStrategy, never()).delete(any());
  }

  @Test
  @DisplayName(
      "deleteChart() answers the IOException of the size lookup as errorOccurred (500), deleting nothing")
  void deleteChartAnswersTheIoExceptionAsErrorOccurred() throws IOException {
    final var path = StoragePath.of(REPO_UUID, "charts/a-1.0.0.tgz");
    when(this.storageStrategy.getFileUsage(path, REPO_NAME)).thenThrow(new IOException("disk"));
    final var service = new TestStorageService(this.storageStrategy);

    assertThatThrownBy(() -> service.deleteChart(path, REPO_NAME))
        .isInstanceOf(ErrorOccurredException.class)
        .hasCauseInstanceOf(IOException.class);
    verify(this.storageStrategy, never()).delete(any());
  }

  @Test
  @DisplayName(
      "deleteChartFile() answers the IOException of the size lookup as errorOccurred (500)")
  void deleteChartFileAnswersTheIoExceptionAsErrorOccurred() throws IOException {
    when(this.storageStrategy.getFileUsage(any(), eq(REPO_NAME)))
        .thenThrow(new IOException("disk"));
    final var service = new TestStorageService(this.storageStrategy);

    assertThatThrownBy(
            () ->
                service.deleteChartFile(
                    new RepoRef(REPO_UUID, REPO_NAME), "a-1.0.0.tgz", "sha256:abc"))
        .isInstanceOf(ErrorOccurredException.class)
        .hasCauseInstanceOf(IOException.class);
    verify(this.storageStrategy, never()).delete(any());
  }

  @Test
  @DisplayName(
      "deleteManifestFile() answers the IOException of the size lookup as errorOccurred (500)")
  void deleteManifestFileAnswersTheIoExceptionAsErrorOccurred() throws IOException {
    when(this.storageStrategy.getFileUsage(any(), eq(REPO_NAME)))
        .thenThrow(new IOException("disk"));
    final var service = new TestStorageService(this.storageStrategy);

    assertThatThrownBy(
            () ->
                service.deleteManifestFile(new RepoRef(REPO_UUID, REPO_NAME), "payments", "1.0.0"))
        .isInstanceOf(ErrorOccurredException.class)
        .hasCauseInstanceOf(IOException.class);
    verify(this.storageStrategy, never()).delete(any());
  }

  @Test
  @DisplayName(
      "deleteBlob() answers the IOException of the size lookup as errorOccurred (500), deleting nothing")
  void deleteBlobAnswersTheIoExceptionAsErrorOccurred() throws IOException {
    final var blob = mock(Resource.class);
    when(blob.contentLength()).thenThrow(new IOException("disk"));
    when(this.storageStrategy.get(any(), eq(REPO_NAME))).thenReturn(Optional.of(blob));
    final var service = new TestStorageService(this.storageStrategy);

    assertThatThrownBy(() -> service.deleteBlob(new RepoRef(REPO_UUID, REPO_NAME), "sha256:abc"))
        .isInstanceOf(ErrorOccurredException.class)
        .hasCauseInstanceOf(IOException.class);
    verify(this.storageStrategy, never()).delete(any());
  }
}
