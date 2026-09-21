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

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackage;
import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackageVersion;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageRepository;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageVersionRepository;
import io.repsy.os.server.security.scan.dtos.ScanStatus;
import io.repsy.os.server.security.scan.entities.VulnerabilityScan;
import io.repsy.os.server.security.scan.repositories.VulnerabilityScanRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * RPS-1059: versions that were stored with build metadata before RPS-996 dropped it from the
 * canonical version are moved to the canonical version, row, scans and files.
 */
@DisplayName("NuGet build metadata version migration (RPS-1059)")
class NuGetBuildMetadataVersionMigrationServiceIT extends AbstractIntegrationTest {

  private static final String PACKAGE_ID = "some.package";
  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private NuGetBuildMetadataVersionMigrationService migrationService;
  @Autowired private NuGetPackageRepository packageRepository;
  @Autowired private NuGetPackageVersionRepository versionRepository;
  @Autowired private VulnerabilityScanRepository scanRepository;

  private Repo nugetRepo() {
    return this.seedRepo(RepoType.NUGET, uniqueRepoName("nuget"));
  }

  private NuGetPackage pkg(final Repo repo) {
    final var pkg = new NuGetPackage();
    pkg.setRepo(this.entityManager.getReference(Repo.class, repo.getId()));
    pkg.setPackageId(PACKAGE_ID);
    return this.packageRepository.saveAndFlush(pkg);
  }

  /** Stores a version row as it is stored, so a legacy one carries the build metadata. */
  private NuGetPackageVersion version(
      final NuGetPackage pkg, final String version, final Instant publishedAt) {
    final var row = new NuGetPackageVersion();
    row.setNugetPackage(pkg);
    row.setVersion(version);
    row.setPrerelease(version.contains("-"));
    row.setListed(true);
    row.setPublishedAt(publishedAt);
    row.setDownloadCount(3);
    row.setTitle("Some Package");
    row.setCreatedAt(publishedAt);
    return this.versionRepository.saveAndFlush(row);
  }

  private Path versionDir(final Repo repo, final String version) {
    return storageDirOf(repo).resolve("packages").resolve(PACKAGE_ID).resolve(version);
  }

  /** Writes the files of a version, like a push does. */
  private void files(final Repo repo, final String version, final String content) {
    final var dir = this.versionDir(repo, version);
    try {
      Files.createDirectories(dir);
      Files.writeString(dir.resolve(PACKAGE_ID + "." + version + ".nupkg"), content);
      Files.writeString(dir.resolve(PACKAGE_ID + "." + version + ".nuspec"), content + "-spec");
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void scan(final Repo repo, final String version) {
    final var scan = new VulnerabilityScan();
    scan.setRepo(this.entityManager.getReference(Repo.class, repo.getId()));
    scan.setArtifactName("Some.Package");
    scan.setArtifactVersion(version);
    scan.setStatus(ScanStatus.COMPLETED);
    this.scanRepository.saveAndFlush(scan);
  }

  private List<String> versionsOf(final NuGetPackage pkg) {
    this.entityManager.flush();
    this.entityManager.clear();
    return this.versionRepository.findByNugetPackageIdOrderByPublishedAtDesc(pkg.getId()).stream()
        .map(NuGetPackageVersion::getVersion)
        .toList();
  }

  private static String read(final Path file) throws IOException {
    return Files.readString(file, StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("reports only the versions that carry build metadata")
  void findsOnlyVersionsWithBuildMetadata() {
    final var repo = this.nugetRepo();
    final var pkg = this.pkg(repo);
    final var legacy = this.version(pkg, "1.0.0+build", NOW);
    this.version(pkg, "2.0.0", NOW);

    assertThat(this.migrationService.findLegacyVersions())
        .filteredOn(v -> v.repoId().equals(repo.getId()))
        .singleElement()
        .satisfies(
            v -> {
              assertThat(v.id()).isEqualTo(legacy.getId());
              assertThat(v.packageRowId()).isEqualTo(pkg.getId());
              assertThat(v.repoName()).isEqualTo(repo.getName());
              assertThat(v.packageId()).isEqualTo(PACKAGE_ID);
              assertThat(v.version()).isEqualTo("1.0.0+build");
            });
  }

  @Test
  @DisplayName("renames the row, its scans and its files to the canonical version")
  void migratesRowScansAndFiles() throws IOException {
    final var repo = this.nugetRepo();
    final var pkg = this.pkg(repo);
    final var row = this.version(pkg, "1.0.0+build-1", NOW);
    this.files(repo, "1.0.0+build-1", "payload");
    this.scan(repo, "1.0.0+build-1");
    assertThat(row.isPrerelease()).isTrue();

    final var report = this.migrationService.migrate();

    assertThat(report.migrated()).isGreaterThanOrEqualTo(1);
    assertThat(report.failed()).isZero();
    assertThat(report.conflicts()).noneMatch(c -> c.repoId().equals(repo.getId()));
    assertThat(this.versionsOf(pkg)).containsExactly("1.0.0");

    final var migrated = this.versionRepository.findById(row.getId()).orElseThrow();
    assertThat(migrated.isPrerelease()).isFalse();
    assertThat(migrated.isListed()).isTrue();
    assertThat(migrated.getDownloadCount()).isEqualTo(3);
    assertThat(migrated.getTitle()).isEqualTo("Some Package");

    assertThat(
            this.scanRepository.findAllByRepoIdOrderByCreatedAtDesc(repo.getId()).stream()
                .map(VulnerabilityScan::getArtifactVersion))
        .containsExactly("1.0.0");

    final var canonicalDir = this.versionDir(repo, "1.0.0");
    assertThat(read(canonicalDir.resolve("some.package.1.0.0.nupkg"))).isEqualTo("payload");
    assertThat(read(canonicalDir.resolve("some.package.1.0.0.nuspec"))).isEqualTo("payload-spec");
    assertThat(this.versionDir(repo, "1.0.0+build-1")).doesNotExist();
  }

  @Test
  @DisplayName("keeps a pre-release label and drops only the build metadata")
  void keepsPreReleaseLabel() {
    final var repo = this.nugetRepo();
    final var pkg = this.pkg(repo);
    this.version(pkg, "2.0.0-beta.1+sha.5", NOW);
    this.files(repo, "2.0.0-beta.1+sha.5", "beta");

    this.migrationService.migrate();

    assertThat(this.versionsOf(pkg)).containsExactly("2.0.0-beta.1");
    assertThat(
            this.versionRepository.findAll().stream()
                .filter(v -> v.getVersion().equals("2.0.0-beta.1")))
        .allMatch(NuGetPackageVersion::isPrerelease);
    assertThat(this.versionDir(repo, "2.0.0-beta.1").resolve("some.package.2.0.0-beta.1.nupkg"))
        .exists();
  }

  @Test
  @DisplayName("leaves a version alone when its canonical version already exists")
  void leavesConflictAlone() throws IOException {
    final var repo = this.nugetRepo();
    final var pkg = this.pkg(repo);
    this.version(pkg, "1.0.0", NOW.minusSeconds(60));
    this.version(pkg, "1.0.0+build", NOW);
    this.files(repo, "1.0.0", "canonical");
    this.files(repo, "1.0.0+build", "legacy");
    this.scan(repo, "1.0.0+build");

    final var report = this.migrationService.migrate();

    assertThat(report.conflicts())
        .filteredOn(c -> c.repoId().equals(repo.getId()))
        .singleElement()
        .satisfies(c -> assertThat(c.version()).isEqualTo("1.0.0+build"));
    assertThat(this.versionsOf(pkg)).containsExactlyInAnyOrder("1.0.0", "1.0.0+build");
    assertThat(read(this.versionDir(repo, "1.0.0").resolve("some.package.1.0.0.nupkg")))
        .isEqualTo("canonical");
    assertThat(read(this.versionDir(repo, "1.0.0+build").resolve("some.package.1.0.0+build.nupkg")))
        .isEqualTo("legacy");
    assertThat(
            this.scanRepository.findAllByRepoIdOrderByCreatedAtDesc(repo.getId()).stream()
                .map(VulnerabilityScan::getArtifactVersion))
        .containsExactly("1.0.0+build");
  }

  @Test
  @DisplayName("lets the latest of several legacy versions take the canonical version")
  void newestLegacyVersionWins() throws IOException {
    final var repo = this.nugetRepo();
    final var pkg = this.pkg(repo);
    this.version(pkg, "1.0.0+old", NOW.minusSeconds(60));
    this.version(pkg, "1.0.0+new", NOW);
    this.files(repo, "1.0.0+old", "old");
    this.files(repo, "1.0.0+new", "new");

    final var report = this.migrationService.migrate();

    assertThat(report.conflicts())
        .filteredOn(c -> c.repoId().equals(repo.getId()))
        .singleElement()
        .satisfies(c -> assertThat(c.version()).isEqualTo("1.0.0+old"));
    assertThat(this.versionsOf(pkg)).containsExactlyInAnyOrder("1.0.0", "1.0.0+old");
    assertThat(read(this.versionDir(repo, "1.0.0").resolve("some.package.1.0.0.nupkg")))
        .isEqualTo("new");
    assertThat(this.versionDir(repo, "1.0.0+old").resolve("some.package.1.0.0+old.nupkg")).exists();
  }

  @Test
  @DisplayName("migrates the row of a version whose files are missing")
  void migratesRowWithoutFiles() {
    final var repo = this.nugetRepo();
    final var pkg = this.pkg(repo);
    this.version(pkg, "1.0.0+build", NOW);

    final var report = this.migrationService.migrate();

    assertThat(report.failed()).isZero();
    assertThat(this.versionsOf(pkg)).containsExactly("1.0.0");
  }

  @Test
  @DisplayName("a second run finds nothing left to migrate")
  void isIdempotent() {
    final var repo = this.nugetRepo();
    final var pkg = this.pkg(repo);
    this.version(pkg, "1.0.0+build", NOW);
    this.files(repo, "1.0.0+build", "payload");

    this.migrationService.migrate();

    assertThat(this.migrationService.findLegacyVersions())
        .noneMatch(v -> v.repoId().equals(repo.getId()));
    assertThat(this.migrationService.migrate().migrated()).isZero();
  }
}
