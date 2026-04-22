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
package io.repsy.protocols.golang.shared.storage.services;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;

@RequiredArgsConstructor
@NullMarked
public abstract class AbstractGoStorageService<ID> implements GoStorageService<ID> {

  private static final String ERR_ITEM_NOT_FOUND = "itemNotFound";

  private final StorageStrategy storageStrategy;

  @Override
  public void createRepo(final UUID repoUuid) {
    this.storageStrategy.createDirectory(repoUuid.toString());
  }

  @Override
  public BaseUsages getUsages(
      final StoragePath storagePath, final String repoName, final long contentLength)
      throws IOException {
    return this.storageStrategy.getUsages(storagePath, repoName, contentLength);
  }

  @Override
  public Resource getResource(final String repoName, final StoragePath storagePath) {
    return this.storageStrategy
        .get(storagePath, repoName)
        .orElseThrow(() -> new ItemNotFoundException(ERR_ITEM_NOT_FOUND));
  }

  @Override
  public BaseUsages writeInputStreamToPath(
      final StoragePath storagePath, final InputStream inputStream, final String repoName) {
    return this.storageStrategy.write(repoName, storagePath, inputStream);
  }

  @Override
  public List<StorageItemInfo> listDirectory(final StoragePath storagePath) {
    try {
      return this.storageStrategy.listDirectoryContents(storagePath);
    } catch (final ItemNotFoundException _) {
      return Collections.emptyList();
    }
  }

  @Override
  public void deleteDirectory(final StoragePath storagePath) {
    this.storageStrategy.deleteDirectory(storagePath);
  }

  @Override
  public void deleteVersionFiles(final StoragePath atVVersionBasePath, final String repoName) {
    // atVVersionBasePath points to /{modulePath}/@v/{version} (no extension)
    // List the parent @v/ directory and delete files matching the version prefix
    final var storageKey = atVVersionBasePath.getStorageKey();
    if (storageKey == null) {
      return;
    }
    final var relativePath = atVVersionBasePath.getRelativePath().getPath();
    final var lastSlash = relativePath.lastIndexOf('/');
    if (lastSlash < 0) {
      return;
    }
    final var versionPrefix = relativePath.substring(lastSlash + 1);
    final var atVDirPath = relativePath.substring(0, lastSlash + 1);
    final var atVStoragePath = StoragePath.of(storageKey, atVDirPath);

    try {
      final var items = this.storageStrategy.listDirectoryContents(atVStoragePath);
      for (final var item : items) {
        this.tryDeleteVersionFile(item, versionPrefix, atVDirPath, storageKey);
      }
    } catch (final Exception _) {
      // directory may not exist
    }
  }

  private void tryDeleteVersionFile(
      final StorageItemInfo item,
      final String versionPrefix,
      final String atVDirPath,
      final UUID storageKey) {
    if (item.isDirectory() || !item.getName().startsWith(versionPrefix + ".")) {
      return;
    }
    final var filePath = StoragePath.of(storageKey, atVDirPath + item.getName());
    try {
      this.storageStrategy.delete(filePath);
    } catch (final Exception _) {
      // best-effort
    }
  }

  @Override
  public long deleteRepo(final UUID repoUuid) {
    final var storagePath = StoragePath.of(repoUuid);
    final var usage = this.storageStrategy.calculatePathUsage(storagePath);
    this.storageStrategy.deleteDirectory(storagePath);
    return usage;
  }
}
