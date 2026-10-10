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
package io.repsy.protocols.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ErrorOccurredException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

@DisplayName("AbstractArtifactStorageService")
class AbstractArtifactStorageServiceTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  private final StorageStrategy strategy = mock(StorageStrategy.class);
  private final Service service = new Service(this.strategy);

  private static final class Service extends AbstractArtifactStorageService {

    Service(final StorageStrategy storageStrategy) {
      super(storageStrategy);
    }

    org.springframework.core.io.Resource require(
        final StoragePath path, final String repoName, final String code) {
      return this.requireResource(path, repoName, code);
    }

    long deleteFile(final StoragePath path, final String repoName) {
      return this.deleteFileWithUsage(path, repoName);
    }

    long deleteTree(final StoragePath path) {
      return this.deleteTreeWithUsage(path);
    }
  }

  @Test
  @DisplayName("createRepo() creates the directory named after the repo id")
  void createsRepoDirectory() {
    this.service.createRepo(REPO_ID);

    verify(this.strategy).createDirectory(REPO_ID.toString());
  }

  @Test
  @DisplayName("deleteRepo() deletes the repo directory without sizing it first")
  void deletesRepoDirectory() {
    this.service.deleteRepo(REPO_ID);

    verify(this.strategy)
        .delete(argThat(path -> path != null && REPO_ID.toString().equals(path.getPath())));
    verify(this.strategy, never()).calculatePathUsage(any());
  }

  @Test
  @DisplayName("requireResource() returns the stored file")
  void returnsStoredFile() {
    final var path = StoragePath.of(REPO_ID, "a/b.bin");
    final var resource = new ByteArrayResource(new byte[] {1});
    when(this.strategy.get(path, "repo")).thenReturn(Optional.of(resource));

    assertThat(this.service.require(path, "repo", "itemNotFound")).isSameAs(resource);
  }

  @Test
  @DisplayName("requireResource() throws ItemNotFoundException with the code the format passes")
  void throwsNotFoundWithTheGivenCode() {
    final var path = StoragePath.of(REPO_ID, "a/b.bin");
    when(this.strategy.get(path, "repo")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> this.service.require(path, "repo", "gemNotFound"))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("gemNotFound");
  }

  @Test
  @DisplayName("deleteFileWithUsage() reads the size, deletes the file and returns the size")
  void deletesFileAndReturnsUsage() throws IOException {
    final var path = StoragePath.of(REPO_ID, "a/b.bin");
    when(this.strategy.getFileUsage(path, "repo")).thenReturn(7L);

    assertThat(this.service.deleteFile(path, "repo")).isEqualTo(7L);
    verify(this.strategy).delete(path);
  }

  @Test
  @DisplayName(
      "deleteFileWithUsage() answers the IOException of the size lookup as ErrorOccurredException,"
          + " deleting nothing")
  void deleteFileAnswersTheIoExceptionAsErrorOccurred() throws IOException {
    final var path = StoragePath.of(REPO_ID, "a/b.bin");
    when(this.strategy.getFileUsage(path, "repo")).thenThrow(new IOException("disk"));

    assertThatThrownBy(() -> this.service.deleteFile(path, "repo"))
        .isInstanceOf(ErrorOccurredException.class)
        .hasCauseInstanceOf(IOException.class);
    verify(this.strategy, never()).delete(any());
  }

  @Test
  @DisplayName("deleteTreeWithUsage() sums the tree, deletes it and returns the sum")
  void deletesTreeAndReturnsUsage() {
    final var path = StoragePath.of(REPO_ID, "a");
    when(this.strategy.calculatePathUsage(path)).thenReturn(300L);

    assertThat(this.service.deleteTree(path)).isEqualTo(300L);
    verify(this.strategy).delete(path);
  }

  @Test
  @DisplayName(
      "deleteFileWithUsage() answers zero for a missing file: a missing item is no failure")
  void deletingAMissingFileAnswersZero() throws IOException {
    final var path = StoragePath.of(REPO_ID, "a/missing.bin");
    when(this.strategy.getFileUsage(path, "repo")).thenReturn(0L);

    assertThat(this.service.deleteFile(path, "repo")).isZero();
  }
}
