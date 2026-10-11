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

import static io.repsy.protocols.nuget.shared.utils.NuGetVersionUtils.normalizeNuGetVersion;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.storage.AbstractArtifactStorageService;
import io.repsy.protocols.shared.storage.RepoRef;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Optional;
import org.springframework.core.io.Resource;

public abstract class AbstractNuGetStorageService extends AbstractArtifactStorageService
    implements NuGetStorageService {

  private static final String PACKAGES_PATH = "packages";
  private static final String NUPKG_EXTENSION = ".nupkg";
  private static final String NUSPEC_EXTENSION = ".nuspec";

  protected AbstractNuGetStorageService(final StorageStrategy storageStrategy) {
    super(storageStrategy);
  }

  @Override
  public BaseUsages writePackage(
      final RepoRef repo,
      final String packageId,
      final String version,
      final InputStream nuPkgStream,
      final byte[] nuspecBytes)
      throws IOException {

    final var normalizedId = packageId.toLowerCase(Locale.ROOT);
    final var normalizedVersion = normalizeNuGetVersion(version);

    final var nupkgStoragePath =
        StoragePath.of(repo.id(), filePath(normalizedId, normalizedVersion, NUPKG_EXTENSION));
    final var nuspecStoragePath =
        StoragePath.of(repo.id(), filePath(normalizedId, normalizedVersion, NUSPEC_EXTENSION));

    final var nupkgUsages = this.storageStrategy.write(repo.name(), nupkgStoragePath, nuPkgStream);

    final BaseUsages nuspecUsages;
    try (final var bis = new ByteArrayInputStream(nuspecBytes)) {
      nuspecUsages = this.storageStrategy.write(repo.name(), nuspecStoragePath, bis);
    }

    nupkgUsages.setDiskUsage(nupkgUsages.getDiskUsage() + nuspecUsages.getDiskUsage());
    return nupkgUsages;
  }

  @Override
  public Resource getNuPkg(final RepoRef repo, final String packageId, final String version) {

    return this.getPackageFile(repo, packageId, version, NUPKG_EXTENSION)
        .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.NUPKG_NOT_FOUND));
  }

  @Override
  public Resource getNuspec(final RepoRef repo, final String packageId, final String version) {

    return this.getPackageFile(repo, packageId, version, NUSPEC_EXTENSION)
        .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.NUSPEC_NOT_FOUND));
  }

  @Override
  public String getNupkgRelativePath(final String packageId, final String version) {

    return filePath(
        packageId.toLowerCase(Locale.ROOT), normalizeNuGetVersion(version), NUPKG_EXTENSION);
  }

  @Override
  public boolean copyToCanonicalVersion(
      final RepoRef repo, final String packageId, final String version) throws IOException {

    final var normalizedId = packageId.toLowerCase(Locale.ROOT);
    if (!hasBuildMetadata(version)) {
      return false;
    }

    final var source = buildMetadataDirectory(version);
    final var target = normalizeNuGetVersion(version);

    final var nupkgCopied = this.copyFile(repo, normalizedId, source, target, NUPKG_EXTENSION);
    this.copyFile(repo, normalizedId, source, target, NUSPEC_EXTENSION);
    return nupkgCopied;
  }

  private boolean copyFile(
      final RepoRef repo,
      final String normalizedId,
      final String sourceVersion,
      final String targetVersion,
      final String extension)
      throws IOException {

    final var resource =
        this.storageStrategy.get(
            StoragePath.of(repo.id(), filePath(normalizedId, sourceVersion, extension)),
            repo.name());

    if (resource.isEmpty()) {
      return false;
    }

    try (final var in = resource.get().getInputStream()) {
      this.storageStrategy.write(
          repo.name(),
          StoragePath.of(repo.id(), filePath(normalizedId, targetVersion, extension)),
          in);
    }
    return true;
  }

  @Override
  public long deletePackageVersion(final RepoRef repo, final String packageId, final String version)
      throws IOException {

    final var normalizedId = packageId.toLowerCase(Locale.ROOT);

    return this.deleteVersionDirectory(repo, normalizedId, normalizeNuGetVersion(version));
  }

  @Override
  public long deleteBuildMetadataVersion(
      final RepoRef repo, final String packageId, final String version) throws IOException {

    // A version without build metadata has no directory of its own apart from the canonical one,
    // which must not be removed here.
    if (!hasBuildMetadata(version)) {
      return 0;
    }

    return this.deleteVersionDirectory(
        repo, packageId.toLowerCase(Locale.ROOT), buildMetadataDirectory(version));
  }

  private long deleteVersionDirectory(
      final RepoRef repo, final String normalizedId, final String versionDirectory) {

    final var versionPath = PACKAGES_PATH + "/" + normalizedId + "/" + versionDirectory;
    final var versionStoragePath = StoragePath.of(repo.id(), versionPath);

    return this.deleteTreeWithUsage(versionStoragePath);
  }

  @Override
  public long deletePackage(final RepoRef repo, final String packageId) {

    final var normalizedId = packageId.toLowerCase(Locale.ROOT);
    final var packagePath = PACKAGES_PATH + "/" + normalizedId;
    final var packageStoragePath = StoragePath.of(repo.id(), packagePath);

    return this.deleteTreeWithUsage(packageStoragePath);
  }

  private Optional<Resource> getPackageFile(
      final RepoRef repo, final String packageId, final String version, final String extension) {

    final var normalizedId = packageId.toLowerCase(Locale.ROOT);

    return this.storageStrategy.get(
        StoragePath.of(
            repo.id(), filePath(normalizedId, normalizeNuGetVersion(version), extension)),
        repo.name());
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
