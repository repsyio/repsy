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

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;

@Slf4j
@RequiredArgsConstructor
@NullMarked
public abstract class AbstractDockerStorageService<ID> implements DockerStorageService<ID> {

  private static final String MANIFESTS_PATH = "manifests";

  private final StorageStrategy storageStrategy;

  @Override
  public void createRepo(final UUID repoUuid) {

    this.storageStrategy.createDirectory(repoUuid.toString());
  }

  @Override
  public long deleteRepo(final UUID repoUuid) {

    final var storagePath = StoragePath.of(repoUuid);

    final var usage = this.storageStrategy.calculatePathUsage(storagePath);

    this.storageStrategy.deleteDirectory(storagePath);

    return usage;
  }

  @Override
  public boolean existsResource(final StoragePath storagePath, final String repoName) {

    final var resourceOpt = this.storageStrategy.get(storagePath, repoName);

    return resourceOpt.isPresent() && resourceOpt.get().exists();
  }

  @Override
  public List<StorageItemInfo> getItems(final UUID repoUuid, final RelativePath relativePath) {

    final var storagePath = StoragePath.of(repoUuid, relativePath.getPath());

    return this.storageStrategy.listDirectoryContents(storagePath);
  }

  @Override
  public Optional<Resource> getResource(final StoragePath storagePath, final String repoName) {

    return this.storageStrategy.get(storagePath, repoName);
  }

  @Override
  public BaseUsages getUsages(
      final StoragePath storagePath, final String name, final long contentLength)
      throws IOException {

    return this.storageStrategy.getUsages(storagePath, name, contentLength);
  }

  @Override
  public BaseUsages writeInputStreamToPath(
      final String repoName, final StoragePath storagePath, final InputStream inputStream) {

    return this.storageStrategy.write(repoName, storagePath, inputStream);
  }

  @Override
  public long deleteManifests(
      final BaseRepoInfo<ID> repoInfo, final Collection<String> manifestsToDeleteFileNames) {

    var totalUsage = 0L;

    for (final var manifestToDeleteFileName : manifestsToDeleteFileNames) {
      totalUsage += this.deleteManifest(repoInfo, manifestToDeleteFileName);
    }

    return totalUsage;
  }

  @Override
  public long deleteManifest(final BaseRepoInfo<ID> repoInfo, final String manifestName) {

    try {
      final var storagePath =
          StoragePath.of(
              repoInfo.getStorageKey(), Paths.get(MANIFESTS_PATH, manifestName).toString());

      final var usage = this.storageStrategy.getFileUsage(storagePath, repoInfo.getName());

      this.storageStrategy.delete(storagePath);

      return usage;
    } catch (final IOException e) {
      log.warn("Failed to delete manifest {} while calculating manifest usage", manifestName, e);
      return 0L;
    }
  }

  @Override
  public void rename(final UUID repoUuid, final RelativePath relativePath, final String digest) {

    final var storagePath = StoragePath.of(repoUuid, relativePath.getPath());

    this.storageStrategy.renameObject(storagePath, digest);
  }

  @Override
  public void clearTrash() {

    this.storageStrategy.clearTrash();
  }
}
