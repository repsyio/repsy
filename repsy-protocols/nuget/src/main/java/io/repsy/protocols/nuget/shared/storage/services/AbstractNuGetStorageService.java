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

    return filePath(
        packageId.toLowerCase(Locale.ROOT), normalizeNuGetVersion(version), NUPKG_EXTENSION);
  }

  @Override
  public boolean copyToCanonicalVersion(
      final UUID repoId, final String packageId, final String version) throws IOException {

    final var normalizedId = packageId.toLowerCase(Locale.ROOT);
    if (!hasBuildMetadata(version)) {
      return false;
    }

    final var source = buildMetadataDirectory(version);
    final var target = normalizeNuGetVersion(version);

    final var nupkgCopied = this.copyFile(repoId, normalizedId, source, target, NUPKG_EXTENSION);
    this.copyFile(repoId, normalizedId, source, target, NUSPEC_EXTENSION);
    return nupkgCopied;
  }

  private boolean copyFile(
      final UUID repoId,
      final String normalizedId,
      final String sourceVersion,
      final String targetVersion,
      final String extension)
      throws IOException {

    final var repoName = repoId.toString();
    final var resource =
        this.storageStrategy.get(
            StoragePath.of(repoId, filePath(normalizedId, sourceVersion, extension)), repoName);

    if (resource.isEmpty()) {
      return false;
    }

    try (final var in = resource.get().getInputStream()) {
      this.storageStrategy.write(
          repoName, StoragePath.of(repoId, filePath(normalizedId, targetVersion, extension)), in);
    }
    return true;
  }

  @Override
  public long deletePackageVersion(final UUID repoId, final String packageId, final String version)
      throws IOException {

    final var normalizedId = packageId.toLowerCase(Locale.ROOT);

    return this.deleteVersionDirectory(repoId, normalizedId, normalizeNuGetVersion(version));
  }

  @Override
  public long deleteBuildMetadataVersion(
      final UUID repoId, final String packageId, final String version) throws IOException {

    // A version without build metadata has no directory of its own apart from the canonical one,
    // which must not be removed here.
    if (!hasBuildMetadata(version)) {
      return 0;
    }

    return this.deleteVersionDirectory(
        repoId, packageId.toLowerCase(Locale.ROOT), buildMetadataDirectory(version));
  }

  private long deleteVersionDirectory(
      final UUID repoId, final String normalizedId, final String versionDirectory) {

    final var versionPath = PACKAGES_PATH + "/" + normalizedId + "/" + versionDirectory;
    final var versionStoragePath = StoragePath.of(repoId, versionPath);

    final var usage = this.storageStrategy.calculatePathUsage(versionStoragePath);
    this.storageStrategy.delete(versionStoragePath);

    return usage;
  }

  @Override
  public long deletePackage(final UUID repoId, final String packageId) {

    final var normalizedId = packageId.toLowerCase(Locale.ROOT);
    final var packagePath = PACKAGES_PATH + "/" + normalizedId;
    final var packageStoragePath = StoragePath.of(repoId, packagePath);

    final var usage = this.storageStrategy.calculatePathUsage(packageStoragePath);
    this.storageStrategy.delete(packageStoragePath);

    return usage;
  }

  @Override
  public void deleteRepo(final UUID repoId) {
    final var storagePath = StoragePath.of(repoId);
    this.storageStrategy.delete(storagePath);
  }

  private Optional<Resource> getPackageFile(
      final UUID repoId, final String packageId, final String version, final String extension) {

    final var normalizedId = packageId.toLowerCase(Locale.ROOT);

    return this.storageStrategy.get(
        StoragePath.of(repoId, filePath(normalizedId, normalizeNuGetVersion(version), extension)),
        repoId.toString());
  }

  /**
   * The version directory of a version stored with build metadata, which is the version as it was
   * stored: only {@link #copyToCanonicalVersion} and {@link #deleteBuildMetadataVersion}, the
   * migration of those versions (RPS-1059), read or remove it.
   */
  private static boolean hasBuildMetadata(final String version) {
    return version.indexOf('+') >= 0;
  }

  private static String buildMetadataDirectory(final String version) {
    return version.strip().toLowerCase(Locale.ROOT);
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
