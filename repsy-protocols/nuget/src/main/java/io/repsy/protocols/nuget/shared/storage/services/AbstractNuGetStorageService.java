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

import static io.repsy.protocols.nuget.shared.utils.NuGetPackageUtils.hasBuildMetadata;
import static io.repsy.protocols.nuget.shared.utils.NuGetPackageUtils.legacyNuGetVersion;
import static io.repsy.protocols.nuget.shared.utils.NuGetPackageUtils.normalizeNuGetVersion;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;

@NullMarked
@RequiredArgsConstructor
public abstract class AbstractNuGetStorageService implements NuGetStorageService {

  private static final String PACKAGES_PATH = "packages";
  private static final String NUPKG_EXTENSION = ".nupkg";
  private static final String NUSPEC_EXTENSION = ".nuspec";

  private final StorageStrategy storageStrategy;

  @Override
  public BaseUsages writePackage(
      final UUID repoId,
      final String packageId,
      final String version,
      final InputStream nuPkgStream,
      final byte[] nuspecBytes)
      throws IOException {

    final var normalizedId = packageId.toLowerCase(Locale.ROOT);
    final var normalizedVersion = normalizeNuGetVersion(version);

    final var nupkgStoragePath =
        StoragePath.of(repoId, filePath(normalizedId, normalizedVersion, NUPKG_EXTENSION));
    final var nuspecStoragePath =
        StoragePath.of(repoId, filePath(normalizedId, normalizedVersion, NUSPEC_EXTENSION));

    final var nupkgUsages =
        this.storageStrategy.write(repoId.toString(), nupkgStoragePath, nuPkgStream);

    final BaseUsages nuspecUsages;
    try (final var bis = new ByteArrayInputStream(nuspecBytes)) {
      nuspecUsages = this.storageStrategy.write(repoId.toString(), nuspecStoragePath, bis);
    }

    nupkgUsages.setDiskUsage(nupkgUsages.getDiskUsage() + nuspecUsages.getDiskUsage());
    return nupkgUsages;
  }

  @Override
  public Resource getNuPkg(final UUID repoId, final String packageId, final String version) {

    return this.getPackageFile(repoId, packageId, version, NUPKG_EXTENSION)
        .orElseThrow(() -> new ItemNotFoundException("nupkgNotFound"));
  }

  @Override
  public Resource getNuspec(final UUID repoId, final String packageId, final String version) {

    return this.getPackageFile(repoId, packageId, version, NUSPEC_EXTENSION)
        .orElseThrow(() -> new ItemNotFoundException("nuspecNotFound"));
  }

  @Override
  public void createRepo(final UUID repoId) {
    this.storageStrategy.createDirectory(repoId.toString());
  }

  @Override
  public String getNupkgRelativePath(final String packageId, final String version) {

    return filePath(packageId.toLowerCase(Locale.ROOT), storedVersion(version), NUPKG_EXTENSION);
  }

  @Override
  public long deletePackageVersion(final UUID repoId, final String packageId, final String version)
      throws IOException {

    final var normalizedId = packageId.toLowerCase(Locale.ROOT);

    final var versionPath = PACKAGES_PATH + "/" + normalizedId + "/" + storedVersion(version);
    final var versionStoragePath = StoragePath.of(repoId, versionPath);

    final var usage = this.storageStrategy.calculatePathUsage(versionStoragePath);
    this.storageStrategy.deleteDirectory(versionStoragePath);

    return usage;
  }

  @Override
  public long deletePackage(final UUID repoId, final String packageId) {

    final var normalizedId = packageId.toLowerCase(Locale.ROOT);
    final var packagePath = PACKAGES_PATH + "/" + normalizedId;
    final var packageStoragePath = StoragePath.of(repoId, packagePath);

    final var usage = this.storageStrategy.calculatePathUsage(packageStoragePath);
    this.storageStrategy.deleteDirectory(packageStoragePath);

    return usage;
  }

  @Override
  public void deleteRepo(final UUID repoId) {
    final var storagePath = StoragePath.of(repoId);
    this.storageStrategy.deleteDirectory(storagePath);
  }

  /**
   * Reads a package file. A version with build metadata is looked up where it was stored before the
   * metadata was dropped from the canonical form, and then under the canonical version, which is
   * where a package pushed with that metadata now lives.
   */
  private Optional<Resource> getPackageFile(
      final UUID repoId, final String packageId, final String version, final String extension) {

    final var normalizedId = packageId.toLowerCase(Locale.ROOT);
    final var stored =
        this.storageStrategy.get(
            StoragePath.of(repoId, filePath(normalizedId, storedVersion(version), extension)),
            repoId.toString());

    if (stored.isPresent() || !hasBuildMetadata(version)) {
      return stored;
    }

    return this.storageStrategy.get(
        StoragePath.of(repoId, filePath(normalizedId, normalizeNuGetVersion(version), extension)),
        repoId.toString());
  }

  /**
   * The version directory of an already stored version. A version with build metadata can only be
   * one stored before the metadata was dropped, so it keeps the directory it was written to.
   */
  private static String storedVersion(final String version) {
    return hasBuildMetadata(version) ? legacyNuGetVersion(version) : normalizeNuGetVersion(version);
  }

  private static String filePath(
      final String normalizedId, final String versionDirectory, final String extension) {

    return PACKAGES_PATH
        + "/"
        + normalizedId
        + "/"
        + versionDirectory
        + "/"
        + normalizedId
        + "."
        + versionDirectory
        + extension;
  }
}
