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
package io.repsy.os.server.protocols.nuget.shared.packages.services;

import static io.repsy.protocols.nuget.shared.utils.NuGetPackageUtils.normalizeNuGetVersion;

import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetBuildMetadataVersion;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageVersionRepository;
import io.repsy.os.server.security.scan.repositories.VulnerabilityScanRepository;
import io.repsy.protocols.nuget.shared.storage.services.NuGetStorageService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Migrates the NuGet versions that were stored with build metadata ({@code 1.0.0+build}) to their
 * canonical version ({@code 1.0.0}) (RPS-1059).
 *
 * <p>Before RPS-996 a package pushed as {@code 1.0.0+build} was stored, listed and served under
 * that string, its files under {@code packages/<id>/1.0.0+build/}. NuGet ignores build metadata
 * when it compares versions and clients ask for the version without it, so such a package could not
 * be resolved. The migration renames the row, its vulnerability scans and its files to the
 * canonical version. The files are copied first, the row is renamed in a transaction of its own,
 * and only then are the old files removed, so a failure never leaves a row without its files and
 * the next start simply tries again.
 *
 * <p>A version whose canonical form is already taken by another version of the same package is left
 * exactly as it is, and reported. Nothing is dropped automatically, since the two rows hold two
 * different uploads. When several legacy versions of one package share a canonical version, the one
 * that was published last takes it.
 *
 * <p>A file move keeps the repo disk usage as it is: the same bytes are stored under another path.
 */
@Slf4j
@NullMarked
@Service
public class NuGetBuildMetadataVersionMigrationService {

  private final NuGetPackageVersionRepository packageVersionRepository;
  private final VulnerabilityScanRepository vulnerabilityScanRepository;
  private final NuGetStorageService nuGetStorageService;
  private final TransactionTemplate transactionTemplate;

  public NuGetBuildMetadataVersionMigrationService(
      final NuGetPackageVersionRepository packageVersionRepository,
      final VulnerabilityScanRepository vulnerabilityScanRepository,
      final NuGetStorageService nuGetStorageService,
      final PlatformTransactionManager transactionManager) {

    this.packageVersionRepository = packageVersionRepository;
    this.vulnerabilityScanRepository = vulnerabilityScanRepository;
    this.nuGetStorageService = nuGetStorageService;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /** What one run of {@link #migrate()} did. */
  public record MigrationReport(
      int migrated, int failed, List<NuGetBuildMetadataVersion> conflicts) {

    public boolean isEmpty() {
      return this.migrated == 0 && this.failed == 0 && this.conflicts.isEmpty();
    }
  }

  /** The versions that are stored with build metadata. */
  public List<NuGetBuildMetadataVersion> findLegacyVersions() {
    return this.packageVersionRepository.findAllWithBuildMetadata();
  }

  /**
   * Migrates every version stored with build metadata. A version that fails is logged and counted,
   * and does not stop the others.
   */
  public MigrationReport migrate() {
    var migrated = 0;
    var failed = 0;
    final var conflicts = new ArrayList<NuGetBuildMetadataVersion>();

    for (final var legacy : this.findLegacyVersions()) {
      final var canonical = normalizeNuGetVersion(legacy.version());

      if (this.packageVersionRepository.existsByNugetPackageIdAndVersionIgnoreCase(
          legacy.packageRowId(), canonical)) {
        log.warn(
            "NuGet package {} {} of repo {} carries build metadata, but its canonical version {}"
                + " already exists. Left as it is.",
            legacy.packageId(),
            legacy.version(),
            legacy.repoName(),
            canonical);
        conflicts.add(legacy);
        continue;
      }

      try {
        this.migrateOne(legacy, canonical);
        migrated++;
      } catch (final IOException | RuntimeException e) {
        log.error(
            "Could not migrate NuGet package {} {} of repo {} to {}",
            legacy.packageId(),
            legacy.version(),
            legacy.repoName(),
            canonical,
            e);
        failed++;
      }
    }

    return new MigrationReport(migrated, failed, List.copyOf(conflicts));
  }

  private void migrateOne(final NuGetBuildMetadataVersion legacy, final String canonical)
      throws IOException {

    final var packageId = legacy.packageId().toLowerCase(Locale.ROOT);

    final var filesFound =
        this.nuGetStorageService.copyToCanonicalVersion(
            legacy.repoId(), packageId, legacy.version());
    if (!filesFound) {
      log.warn(
          "NuGet package {} {} of repo {} has no file to move. Only its version is migrated.",
          legacy.packageId(),
          legacy.version(),
          legacy.repoName());
    }

    this.transactionTemplate.executeWithoutResult(_ -> this.renameRows(legacy, canonical));

    // Only after the rename is committed, so a failed rename never leaves the row without files.
    if (filesFound) {
      this.deleteLegacyFiles(legacy, packageId);
    }
  }

  private void renameRows(final NuGetBuildMetadataVersion legacy, final String canonical) {
    final var version = this.packageVersionRepository.findById(legacy.id()).orElseThrow();
    version.setVersion(canonical);
    version.setPrerelease(canonical.contains("-"));
    this.packageVersionRepository.saveAndFlush(version);

    this.vulnerabilityScanRepository.updateArtifactVersion(
        legacy.repoId(), legacy.packageId().toLowerCase(Locale.ROOT), legacy.version(), canonical);
  }

  private void deleteLegacyFiles(final NuGetBuildMetadataVersion legacy, final String packageId) {
    try {
      this.nuGetStorageService.deletePackageVersion(legacy.repoId(), packageId, legacy.version());
    } catch (final IOException | RuntimeException e) {
      log.warn(
          "Could not remove the files NuGet package {} {} of repo {} was moved out of",
          legacy.packageId(),
          legacy.version(),
          legacy.repoName(),
          e);
    }
  }
}
