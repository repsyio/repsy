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
package io.repsy.protocols.npm.shared.storage;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.protocols.npm.shared.utils.NpmPackageUtils;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.storage.RepoRef;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;

/**
 * The tarball files of an npm package in storage: what is written at a publish, read to serve or to
 * rebuild a packument, and removed with a version. It knows nothing of the packument that goes with
 * them.
 */
@NullMarked
public final class NpmTarballStore {

  private final StorageStrategy storageStrategy;

  public NpmTarballStore(final StorageStrategy storageStrategy) {
    this.storageStrategy = storageStrategy;
  }

  private static StoragePath storagePath(
      final UUID repoId,
      final Path packageBasePath,
      final String packageName,
      final String versionName) {

    final var tarballPath =
        packageBasePath.resolve(NpmPackageUtils.getTarballFilename(packageName, versionName));

    return StoragePath.of(repoId, tarballPath.toString());
  }

  /** The tarball of the version, when it is in storage. */
  public Optional<Resource> find(
      final RepoRef repo,
      final Path packageBasePath,
      final String packageName,
      final String versionName) {

    return this.storageStrategy.get(
        storagePath(repo.id(), packageBasePath, packageName, versionName), repo.name());
  }

  public boolean exists(
      final RepoRef repo,
      final Path packageBasePath,
      final String packageName,
      final String versionName) {

    return this.find(repo, packageBasePath, packageName, versionName).isPresent();
  }

  /** The file {@code filename} of a package, which is not found when it is not in storage. */
  public Resource get(final RepoRef repo, final Path packageBasePath, final String filename) {

    final var tarballPath = packageBasePath.resolve(filename).normalize();

    return this.storageStrategy
        .get(StoragePath.of(repo.id(), tarballPath.toString()), repo.name())
        .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.ITEM_NOT_FOUND));
  }

  public BaseUsages write(
      final RepoRef repo,
      final Path packageBasePath,
      final String packageName,
      final String versionName,
      final byte[] tarballBytes)
      throws IOException {

    try (final var inputStream = new ByteArrayInputStream(tarballBytes)) {
      return this.storageStrategy.write(
          repo.name(),
          storagePath(repo.id(), packageBasePath, packageName, versionName),
          inputStream);
    }
  }

  /** Removes the tarball, if there is one. */
  public void deleteIfPresent(
      final RepoRef repo,
      final Path packageBasePath,
      final String packageName,
      final String versionName) {

    final var storagePath = storagePath(repo.id(), packageBasePath, packageName, versionName);

    if (this.storageStrategy.get(storagePath, repo.name()).isPresent()) {
      this.storageStrategy.delete(storagePath);
    }
  }

  /** Removes the tarball, if there is one, and tells how many bytes it took. */
  public long remove(
      final RepoRef repo,
      final Path packageBasePath,
      final String packageName,
      final String versionName)
      throws IOException {

    final var storagePath = storagePath(repo.id(), packageBasePath, packageName, versionName);

    final var tarballSize = this.storageStrategy.getFileUsage(storagePath, repo.name());

    // A version whose tarball is already gone (an interrupted removal) can still be removed.
    if (this.storageStrategy.get(storagePath, repo.name()).isPresent()) {
      this.storageStrategy.delete(storagePath);
    }

    return tarballSize;
  }
}
