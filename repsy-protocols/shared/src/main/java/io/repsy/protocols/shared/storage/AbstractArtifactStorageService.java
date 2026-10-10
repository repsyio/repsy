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

import io.repsy.core.error_handling.exceptions.ErrorOccurredException;
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
 * <p>One failure contract for every format (RPS-2168). A file that is not there is not a failure of
 * a delete: it holds no bytes, the size lookup answers zero and the delete is a no-op, so a missing
 * item is answered (404) by the layer that knows the item, never by the storage service. {@link
 * #requireResource} is the read side of that: {@link ItemNotFoundException} carrying the code the
 * format passes. A failing size lookup (an {@link IOException}) is a storage failure and is never
 * answered as a missing item: it is an {@link ErrorOccurredException} (500 {@code errorOccurred}),
 * and nothing is deleted. A strategy that is unavailable throws its own unchecked exception (503)
 * from the delete, which passes through unchanged.
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
   * The bytes of one file, zero when there is none. A failing lookup is answered as {@link
   * ErrorOccurredException} (500), never as a missing item.
   */
  protected long fileUsage(final StoragePath storagePath, final String repoName) {
    try {
      return this.storageStrategy.getFileUsage(storagePath, repoName);
    } catch (final IOException e) {
      throw new ErrorOccurredException(e);
    }
  }

  /**
   * Deletes one file and returns the bytes it held. The size is read first so it can be refunded;
   * when that lookup fails nothing is deleted.
   */
  protected long deleteFileWithUsage(final StoragePath storagePath, final String repoName) {
    final var usage = this.fileUsage(storagePath, repoName);
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
