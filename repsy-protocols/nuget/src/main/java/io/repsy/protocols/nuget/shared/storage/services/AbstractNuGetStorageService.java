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
package io.repsy.protocols.nuget.shared.storage.services;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;

@NullMarked
@RequiredArgsConstructor
public abstract class AbstractNuGetStorageService implements NuGetStorageService {

  private static final String PACKAGES_PATH = "packages";

  private final StorageStrategy storageStrategy;

  @Override
  public BaseUsages writePackage(
      final UUID repoId,
      final String packageId,
      final String version,
      final byte[] nupkgBytes,
      final byte[] nuspecBytes)
      throws IOException {

    final var normalizedId = packageId.toLowerCase(Locale.ROOT);
    final var normalizedVersion = version.toLowerCase(Locale.ROOT);

    final var nupkgPath =
        PACKAGES_PATH + "/" + normalizedId + "/" + normalizedVersion + "/" + normalizedId + "."
            + normalizedVersion + ".nupkg";
    final var nuspecPath =
        PACKAGES_PATH + "/" + normalizedId + "/" + normalizedVersion + "/" + normalizedId + "."
            + normalizedVersion + ".nuspec";

    final var nupkgStoragePath = StoragePath.of(repoId, nupkgPath);
    final var nuspecStoragePath = StoragePath.of(repoId, nuspecPath);

    final BaseUsages nupkgUsages;
    try (final var bis = new ByteArrayInputStream(nupkgBytes)) {
      nupkgUsages = this.storageStrategy.write(repoId.toString(), nupkgStoragePath, bis);
    }

    final BaseUsages nuspecUsages;
    try (final var bis = new ByteArrayInputStream(nuspecBytes)) {
      nuspecUsages = this.storageStrategy.write(repoId.toString(), nuspecStoragePath, bis);
    }

    nupkgUsages.setDiskUsage(nupkgUsages.getDiskUsage() + nuspecUsages.getDiskUsage());
    return nupkgUsages;
  }

  @Override
  public Resource getNupkg(final UUID repoId, final String packageId, final String version) {

    final var normalizedId = packageId.toLowerCase(Locale.ROOT);
    final var normalizedVersion = version.toLowerCase(Locale.ROOT);

    final var nupkgPath =
        PACKAGES_PATH + "/" + normalizedId + "/" + normalizedVersion + "/" + normalizedId + "."
            + normalizedVersion + ".nupkg";
    final var nupkgStoragePath = StoragePath.of(repoId, nupkgPath);

    return this.storageStrategy
        .get(nupkgStoragePath, repoId.toString())
        .orElseThrow(() -> new ItemNotFoundException("nupkgNotFound"));
  }

  @Override
  public Resource getNuspec(final UUID repoId, final String packageId, final String version) {

    final var normalizedId = packageId.toLowerCase(Locale.ROOT);
    final var normalizedVersion = version.toLowerCase(Locale.ROOT);

    final var nuspecPath =
        PACKAGES_PATH + "/" + normalizedId + "/" + normalizedVersion + "/" + normalizedId + "."
            + normalizedVersion + ".nuspec";
    final var nuspecStoragePath = StoragePath.of(repoId, nuspecPath);

    return this.storageStrategy
        .get(nuspecStoragePath, repoId.toString())
        .orElseThrow(() -> new ItemNotFoundException("nuspecNotFound"));
  }

  @Override
  public void createRepo(final UUID repoId) {
    this.storageStrategy.createDirectory(repoId.toString());
  }

  @Override
  public long deletePackageVersion(final UUID repoId, final String packageId, final String version)
      throws IOException {

    final var normalizedId = packageId.toLowerCase(Locale.ROOT);
    final var normalizedVersion = version.toLowerCase(Locale.ROOT);

    final var versionPath =
        PACKAGES_PATH + "/" + normalizedId + "/" + normalizedVersion;
    final var versionStoragePath = StoragePath.of(repoId, versionPath);

    final var usage = this.storageStrategy.calculatePathUsage(versionStoragePath);
    this.storageStrategy.deleteDirectory(versionStoragePath);

    return usage;
  }

  @Override
  public long deletePackage(final UUID repoId, final String packageId) throws IOException {

    final var normalizedId = packageId.toLowerCase(Locale.ROOT);
    final var packagePath = PACKAGES_PATH + "/" + normalizedId;
    final var packageStoragePath = StoragePath.of(repoId, packagePath);

    final var usage = this.storageStrategy.calculatePathUsage(packageStoragePath);
    this.storageStrategy.deleteDirectory(packageStoragePath);

    return usage;
  }

  @Override
  public long deleteRepo(final UUID repoId) {

    final var storagePath = StoragePath.of(repoId);
    final var usage = this.storageStrategy.calculatePathUsage(storagePath);

    this.storageStrategy.deleteDirectory(storagePath);

    return usage;
  }
}
