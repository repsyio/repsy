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
package io.repsy.protocols.docker.shared.storage.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.libs.storage.core.dtos.StaleFile;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractDockerStorageService")
class AbstractDockerStorageServiceTest {

  private static final UUID REPO_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String DIGEST = "sha256:abc";

  @Mock private StorageStrategy storageStrategy;

  private static class TestStorageService extends AbstractDockerStorageService<UUID> {

    TestStorageService(final StorageStrategy storageStrategy) {
      super(storageStrategy);
    }
  }

  @Test
  @DisplayName("rename() answers the usage the storage strategy freed by dropping a duplicate")
  void renameAnswersTheStrategyUsage() {
    final var expected = BaseUsages.ofDisk(-4096);
    when(this.storageStrategy.renameObject(
            argThat(path -> path != null && path.getPath().equals(REPO_UUID + "/blobs/upload-id")),
            eq(DIGEST)))
        .thenReturn(expected);

    final var usages =
        new TestStorageService(this.storageStrategy)
            .rename(REPO_UUID, new RelativePath("/blobs/upload-id"), DIGEST);

    assertThat(usages).isSameAs(expected);
  }

  @Test
  @DisplayName("appendInputStreamToPath() appends through the storage strategy")
  void appendInputStreamToPathAppendsThroughTheStrategy() {
    final var expected = BaseUsages.ofDisk(2048);
    final var chunk = new ByteArrayInputStream(new byte[2048]);
    final var storagePath = StoragePath.of(REPO_UUID, "blobs/upload-id");
    when(this.storageStrategy.appendStream("repo", storagePath, chunk)).thenReturn(expected);

    final var usages =
        new TestStorageService(this.storageStrategy)
            .appendInputStreamToPath("repo", storagePath, chunk);

    assertThat(usages).isSameAs(expected);
    verify(this.storageStrategy, never()).write(any(), any(), any());
  }

  @Test
  @DisplayName("listStaleBlobFiles() asks the storage strategy for the repo's blobs directory")
  void listStaleBlobFilesListsTheBlobsDirectory() {
    final var threshold = Instant.parse("2026-01-01T00:00:00Z");
    final var expected = List.of(new StaleFile("upload-id", 10L));
    when(this.storageStrategy.listStaleFiles(
            argThat(path -> path != null && path.getPath().equals(REPO_UUID + "/blobs")),
            eq(threshold)))
        .thenReturn(expected);

    final var files =
        new TestStorageService(this.storageStrategy).listStaleBlobFiles(REPO_UUID, threshold);

    assertThat(files).isSameAs(expected);
  }

  @Test
  @DisplayName("deleteBlobFile() answers the bytes the file held and deletes it")
  void deleteBlobFileAnswersTheFileUsage() throws IOException {
    final var path = REPO_UUID + "/blobs/upload-id";
    when(this.storageStrategy.getFileUsage(
            argThat(storagePath -> storagePath != null && storagePath.getPath().equals(path)),
            eq("repo")))
        .thenReturn(4096L);

    final var freed =
        new TestStorageService(this.storageStrategy).deleteBlobFile(REPO_UUID, "repo", "upload-id");

    assertThat(freed).isEqualTo(4096L);
    final var order = inOrder(this.storageStrategy);
    order
        .verify(this.storageStrategy)
        .getFileUsage(argThat(sp -> sp.getPath().equals(path)), eq("repo"));
    order.verify(this.storageStrategy).delete(argThat(sp -> sp.getPath().equals(path)));
  }
}
