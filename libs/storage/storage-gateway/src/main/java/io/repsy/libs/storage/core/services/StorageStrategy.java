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
package io.repsy.libs.storage.core.services;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.exceptions.IsADirectoryException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.springframework.core.io.Resource;

public interface StorageStrategy {

  void createDirectory(@NonNull String name) throws IsADirectoryException;

  void deleteDirectory(@NonNull StoragePath storagePath);

  void delete(@NonNull StoragePath storagePath);

  void clearTrash();

  long calculatePathUsage(@NonNull StoragePath paths);

  long getFileUsage(@NonNull StoragePath storagePath, @NonNull String repoName) throws IOException;

  void renameObject(@NonNull StoragePath storagePath, @NonNull String digest);

  @NonNull BaseUsages write(
      @NonNull String repoName, @NonNull StoragePath storagePath, @NonNull InputStream inputStream);

  @NonNull Optional<Resource> get(@NonNull StoragePath path, @NonNull String repoName)
      throws IsADirectoryException;

  /** List all items in the storage path. (not including subdirectories) */
  @NonNull List<StorageItemInfo> listDirectoryContents(@NonNull StoragePath storagePath);

  /**
   * List all items in the storage path including subdirectories if repoUuid is not null, this
   * method lists all items in the repository, including items in all subdirectories. If repoUuid is
   * null, only lists items in the storage path.
   */
  @NonNull List<StorageItemInfo> listStorageItems(@NonNull StoragePath storagePath);

  @NonNull BaseUsages getUsages(
      @NonNull StoragePath storagePath, @NonNull String repoName, long contentLength)
      throws IOException;
}
