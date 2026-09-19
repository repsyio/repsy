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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.services.StorageStrategy;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractHelmStorageService blob uploads")
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
  @DisplayName("saveBlobChunk() answers the usage the chunk added to the upload file")
  void saveBlobChunkAnswersTheWrittenUsage() {
    final var expected = BaseUsages.ofDisk(2048);
    when(this.storageStrategy.write(
            eq(REPO_NAME),
            argThat(path -> path != null && path.getPath().equals(UPLOAD_PATH)),
            any()))
        .thenReturn(expected);

    final var usages =
        new TestStorageService(this.storageStrategy)
            .saveBlobChunk(
                REPO_UUID, UPLOAD_ID, new ByteArrayInputStream(new byte[2048]), REPO_NAME);

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
}
