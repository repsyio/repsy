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

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import java.io.IOException;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;

/**
 * The skeleton every format's storage service shares: one {@link StorageStrategy}, the repo
 * directory lifecycle and the three storage moves each service repeated (read a file that must
 * exist, delete a file and report its size, delete a directory tree and report its size).
 *
 * <p>The error contract of those moves is the one the services already had, and is deliberately
 * left to the caller where formats differ: {@link #requireResource} throws {@link
 * ItemNotFoundException} with the code the format passes (a 404), while {@link
 * #deleteFileWithUsage} lets the {@link IOException} of the size lookup through, so each service
 * decides whether a failing delete is an error of its own (Cargo: 500) or a missing item (Ruby:
 * {@code gemNotFound}, 404). Neither move deletes anything when the size lookup fails.
 */
@NullMarked
public abstract class AbstractArtifactStorageService {

  protected final StorageStrategy storageStrategy;

  protected AbstractArtifactStorageService(final StorageStrategy storageStrategy) {
    this.storageStrategy = storageStrategy;
  }

  public void createRepo(final UUID repoId) {
    this.storageStrategy.createDirectory(repoId.toString());
  }

  public void deleteRepo(final UUID repoId) {
    this.storageStrategy.delete(StoragePath.of(repoId));
  }

  /**
   * The stored file, or {@link ItemNotFoundException} carrying {@code notFoundCode} when there is
   * none.
   */
  protected Resource requireResource(
      final StoragePath storagePath, final String repoName, final String notFoundCode) {
    return this.storageStrategy
        .get(storagePath, repoName)
        .orElseThrow(() -> new ItemNotFoundException(notFoundCode));
  }

  /**
   * Deletes one file and returns the bytes it held. The size is read first so it can be refunded;
   * when that lookup fails nothing is deleted.
   */
  protected long deleteFileWithUsage(final StoragePath storagePath, final String repoName)
      throws IOException {
    final var usage = this.storageStrategy.getFileUsage(storagePath, repoName);
    this.storageStrategy.delete(storagePath);

    return usage;
  }

  /** Deletes a directory and everything under it, and returns the bytes it held. */
  protected long deleteTreeWithUsage(final StoragePath storagePath) {
    final var usage = this.storageStrategy.calculatePathUsage(storagePath);
    this.storageStrategy.delete(storagePath);

    return usage;
  }
}
