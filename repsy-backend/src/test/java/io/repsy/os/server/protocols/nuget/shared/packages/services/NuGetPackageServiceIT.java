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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackage;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageRepository;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetDependencyInfo;
import io.repsy.protocols.nuget.shared.packages.services.NuGetPackageService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Regression coverage for persisting NuGet version metadata on PostgreSQL.
 *
 * <p>{@code nuget_package_version.dependencies} is a {@code jsonb} column. Mapping it as a plain
 * {@code String} makes Hibernate bind {@code VARCHAR}, which PostgreSQL rejects with SQLState
 * 42804, so publishing any package that declares a dependency failed. These tests publish through
 * {@link NuGetPackageService#publishVersion} - the path the protocol handlers use - and check both
 * what lands in the column and what the read side returns.
 *
 * <p>This is the only class that covers NuGet dependency persistence; it replaces the overlapping
 * RPS-901 and RPS-904 regression tests (RPS-967).
 */
@DisplayName("NuGetPackageServiceImpl publish/read on PostgreSQL")
class NuGetPackageServiceIT extends AbstractIntegrationTest {

  private static final String PACKAGE_ID = "fixture.package";
  private static final String VERSION = "1.0.0";
  private static final String NO_DEPENDENCIES = "";

  @Autowired private NuGetPackageService<UUID> packageService;
  @Autowired private NuGetPackageRepository packageRepository;
  @Autowired private RepoTxService repoTxService;

  private RepoInfo seedNuGetRepo() {
    final var repo = this.seedRepo(RepoType.NUGET, uniqueRepoName("nuget"));
    return this.repoTxService.getRepoByName(repo.getName());
  }

  @Test
  @DisplayName("propagates non-duplicate integrity violations from package creation")
  void propagatesNonDuplicateIntegrityViolation() {
    final var repoInfo = this.seedNuGetRepo();

    assertThatThrownBy(() -> this.packageService.findOrCreatePackage(repoInfo, PACKAGE_ID))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /**
   * Creates the package row directly. {@code findOrCreatePackage} writes it in a {@code
   * REQUIRES_NEW} transaction, which cannot see this test's uncommitted repo row.
   */
  private UUID createPackage(final RepoInfo repoInfo) {
    final var pkg = new NuGetPackage();
    pkg.setRepo(this.entityManager.getReference(Repo.class, repoInfo.getId()));
    pkg.setPackageId(PACKAGE_ID.toLowerCase(Locale.ROOT));
    final var saved = this.packageRepository.save(pkg);
    this.entityManager.flush();
    return saved.getId();
  }

  private void publish(
      final RepoInfo repoInfo, final UUID packageId, final String version, final String nuspec) {
    this.publish(repoInfo, packageId, version, nuspec, null);
  }

  private void publish(
      final RepoInfo repoInfo,
      final UUID packageId,
      final String version,
      final String nuspec,
      final String readme) {
    try {
      this.packageService.publishVersion(
          repoInfo, packageId, version, nuspec, readme, replacesExisting -> BaseUsages.ofDisk(0));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    this.entityManager.flush();
    this.entityManager.clear();
  }

  private static String nuspec(final String version, final String dependenciesXml) {
    return """
        <?xml version="1.0" encoding="utf-8"?>
        <package>
          <metadata>
            <id>Fixture.Package</id>
            <version>%s</version>
            <title>Fixture Package</title>
            <authors>Repsy</authors>
            <description>Integration fixture</description>
            <tags>fixture searchable</tags>
            <iconUrl>https://example.test/icon.png</iconUrl>
            <licenseUrl>https://example.test/license</licenseUrl>
            <projectUrl>https://example.test/project</projectUrl>
            %s
          </metadata>
        </package>
        """
        .formatted(version, dependenciesXml);
  }

  private Map<String, Object> dependenciesColumn(final UUID packageId, final String version) {
    return this.jdbcTemplate.queryForMap(
        """
        select jsonb_typeof("dependencies") as json_type,
          jsonb_array_length("dependencies") as json_length,
          "dependencies"::text as json_text
        from "public"."nuget_package_version"
        where "package_id" = ? and "version" = ?
        """,
        packageId,
        version);
  }

  @Test
  @DisplayName("stores grouped dependencies as a jsonb array and reads them back")
  void storesGroupedDependenciesAsJsonbArray() {
    final var repoInfo = this.seedNuGetRepo();
    final var packageId = this.createPackage(repoInfo);

    this.publish(
        repoInfo,
        packageId,
        "1.2.3",
        nuspec(
            "1.2.3",
            """
            <dependencies>
              <group targetFramework="net8.0">
                <dependency id="Newtonsoft.Json" version="[13.0.1, )" />
                <dependency id="Serilog" version="3.1.1" />
              </group>
              <group targetFramework=".NETStandard2.0">
                <dependency id="Microsoft.Extensions.Logging" version="[8.0.0,9.0.0)" />
              </group>
            </dependencies>
            """));

    final var column = this.dependenciesColumn(packageId, "1.2.3");
    assertThat(column)
        .containsEntry("json_type", "array")
        .containsEntry("json_length", 3)
        .extractingByKey("json_text")
        .asString()
        .contains("Newtonsoft.Json", "[13.0.1, )", "net8.0", ".NETStandard2.0");

    final var info = this.packageService.findVersionInfo(repoInfo, PACKAGE_ID, "1.2.3");

    assertThat(info).isPresent();
    assertThat(info.get().dependencies())
        .containsExactly(
            new NuGetDependencyInfo("Newtonsoft.Json", "[13.0.1, )", "net8.0"),
            new NuGetDependencyInfo("Serilog", "3.1.1", "net8.0"),
            new NuGetDependencyInfo(
                "Microsoft.Extensions.Logging", "[8.0.0,9.0.0)", ".NETStandard2.0"));
  }

  @Test
  @DisplayName("stores flat, ungrouped dependencies without a target framework")
  void storesFlatDependencies() {
    final var repoInfo = this.seedNuGetRepo();
    final var packageId = this.createPackage(repoInfo);

    this.publish(
        repoInfo,
        packageId,
        "2.0.0",
        nuspec(
            "2.0.0",
            """
            <dependencies>
              <dependency id="Legacy.Dependency" version="1.0.0" />
            </dependencies>
            """));

    assertThat(this.dependenciesColumn(packageId, "2.0.0"))
        .containsEntry("json_type", "array")
        .containsEntry("json_length", 1);

    final var info = this.packageService.findVersionInfo(repoInfo, PACKAGE_ID, "2.0.0");

    assertThat(info).isPresent();
    assertThat(info.get().dependencies())
        .containsExactly(new NuGetDependencyInfo("Legacy.Dependency", "1.0.0", null));
  }

  @Test
  @DisplayName("stores SQL NULL and returns no dependencies when the nuspec declares none")
  void storesNullWhenNoDependencies() {
    final var repoInfo = this.seedNuGetRepo();
    final var packageId = this.createPackage(repoInfo);

    this.publish(repoInfo, packageId, "3.0.0", nuspec("3.0.0", ""));

    assertThat(this.dependenciesColumn(packageId, "3.0.0")).containsEntry("json_type", null);

    final var info = this.packageService.findVersionInfo(repoInfo, PACKAGE_ID, "3.0.0");

    assertThat(info).isPresent();
    assertThat(info.get().dependencies()).isNull();
  }

  @Test
  @DisplayName("stores SQL NULL for a bare nuspec that declares only an id and a version")
  void storesNullForBareNuspec() {
    final var repoInfo = this.seedNuGetRepo();
    final var packageId = this.createPackage(repoInfo);

    this.publish(
        repoInfo,
        packageId,
        "3.1.0",
        "<package><metadata><id>Fixture.Package</id><version>3.1.0</version></metadata></package>");

    assertThat(this.dependenciesColumn(packageId, "3.1.0")).containsEntry("json_type", null);

    final var info = this.packageService.findVersionInfo(repoInfo, PACKAGE_ID, "3.1.0");

    assertThat(info).isPresent();
    assertThat(info.get().dependencies()).isNull();
  }

  @Test
  @DisplayName("persists version metadata alongside dependencies")
  void persistsVersionMetadata() {
    final var repoInfo = this.seedNuGetRepo();
    final var packageId = this.createPackage(repoInfo);

    this.publish(
        repoInfo,
        packageId,
        "4.0.0-beta.1",
        nuspec(
            "4.0.0-beta.1",
            """
            <dependencies>
              <group targetFramework="net8.0">
                <dependency id="Serilog" version="3.1.1" />
              </group>
            </dependencies>
            """));

    final var info = this.packageService.findVersionInfo(repoInfo, PACKAGE_ID, "4.0.0-beta.1");

    assertThat(info).isPresent();
    final var version = info.get();
    assertThat(version.packageId()).isEqualTo(PACKAGE_ID);
    assertThat(version.version()).isEqualTo("4.0.0-beta.1");
    assertThat(version.title()).isEqualTo("Fixture Package");
    assertThat(version.description()).isEqualTo("Integration fixture");
    assertThat(version.authors()).isEqualTo("Repsy");
    assertThat(version.tags()).isEqualTo("fixture searchable");
    assertThat(version.iconUrl()).isEqualTo("https://example.test/icon.png");
    assertThat(version.licenseUrl()).isEqualTo("https://example.test/license");
    assertThat(version.projectUrl()).isEqualTo("https://example.test/project");
    assertThat(version.listed()).isTrue();
    assertThat(version.downloadCount()).isZero();
    assertThat(version.publishedAt()).isNotNull();

    assertThat(
            this.jdbcTemplate.queryForObject(
                """
                select "is_prerelease" from "public"."nuget_package_version"
                where "package_id" = ? and "version" = ?
                """,
                Boolean.class,
                packageId,
                "4.0.0-beta.1"))
        .isTrue();
  }

  @Test
  @DisplayName("stores the repository URL and README and returns them with the version detail")
  void storesRepositoryUrlAndReadme() {
    final var repoInfo = this.seedNuGetRepo();
    final var packageId = this.createPackage(repoInfo);

    this.publish(
        repoInfo,
        packageId,
        "5.0.0",
        nuspec(
            "5.0.0",
            """
            <repository type="git" url="https://github.com/repsyio/fixture" />
            <readme>docs/README.md</readme>
            """),
        "# Fixture\n\nA README.");

    final var info = this.packageService.findVersionInfo(repoInfo, PACKAGE_ID, "5.0.0");

    assertThat(info).isPresent();
    assertThat(info.get().repositoryUrl()).isEqualTo("https://github.com/repsyio/fixture");
    assertThat(info.get().readme()).isEqualTo("# Fixture\n\nA README.");
  }

  @Test
  @DisplayName("keeps the README out of the version list but still returns the repository URL")
  void versionListOmitsReadme() {
    final var repoInfo = this.seedNuGetRepo();
    final var packageId = this.createPackage(repoInfo);

    this.publish(
        repoInfo,
        packageId,
        "5.1.0",
        nuspec("5.1.0", "<repository type=\"git\" url=\"https://github.com/repsyio/fixture\" />"),
        "# Fixture");

    assertThat(this.packageService.getVersionInfos(repoInfo, PACKAGE_ID))
        .singleElement()
        .satisfies(
            v -> {
              assertThat(v.repositoryUrl()).isEqualTo("https://github.com/repsyio/fixture");
              assertThat(v.readme()).isNull();
            });
  }

  @Test
  @DisplayName("leaves the repository URL and README empty when the nuspec declares neither")
  void repositoryUrlAndReadmeAreOptional() {
    final var repoInfo = this.seedNuGetRepo();
    final var packageId = this.createPackage(repoInfo);

    this.publish(repoInfo, packageId, "5.2.0", nuspec("5.2.0", ""));

    final var info = this.packageService.findVersionInfo(repoInfo, PACKAGE_ID, "5.2.0");

    assertThat(info).isPresent();
    assertThat(info.get().repositoryUrl()).isNull();
    assertThat(info.get().readme()).isNull();
  }

  // ---------------------------------------------------------------------------------------------
  // Republishing an existing version (RPS-1013)
  //
  // An override deletes the old row and inserts a new one (RPS-948), so what these tests pin is
  // that nothing of the first publish survives on the row. RPS-948's NuGetPublishProtocolIT test
  // only checks that one row exists and that the stored .nupkg is the replacement's.
  // ---------------------------------------------------------------------------------------------

  /**
   * A nuspec in which every metadata value carries {@code label}, so two publishes never share a
   * value and a row that kept anything of the first one shows up in an assertion.
   */
  private static String labelledNuspec(final String label, final String dependenciesXml) {
    return """
        <?xml version="1.0" encoding="utf-8"?>
        <package>
          <metadata>
            <id>Fixture.Package</id>
            <version>%1$s</version>
            <title>Title %2$s</title>
            <authors>Authors %2$s</authors>
            <description>Description %2$s</description>
            <tags>tags-%2$s</tags>
            <iconUrl>https://example.test/%2$s/icon.png</iconUrl>
            <licenseUrl>https://example.test/%2$s/license</licenseUrl>
            <projectUrl>https://example.test/%2$s/project</projectUrl>
            <repository type="git" url="https://github.com/repsyio/%2$s" />
            %3$s
          </metadata>
        </package>
        """
        .formatted(VERSION, label, dependenciesXml);
  }

  private static String dependencyOn(final String id, final String range, final String framework) {
    return """
        <dependencies>
          <group targetFramework="%s">
            <dependency id="%s" version="%s" />
          </group>
        </dependencies>
        """
        .formatted(framework, id, range);
  }

  private static String readmeOf(final String label) {
    return "# Readme " + label;
  }

  /** Publishes {@link #VERSION} with a nuspec and README that are all {@code label}'s. */
  private void publishLabelled(
      final RepoInfo repoInfo,
      final UUID packageId,
      final String label,
      final String dependenciesXml) {
    this.publish(
        repoInfo, packageId, VERSION, labelledNuspec(label, dependenciesXml), readmeOf(label));
  }

  /** A NuGet repo that rejects a republish of an existing version. */
  private RepoInfo seedNuGetRepoRejectingOverride() {
    final var name = this.seedNuGetRepo().getName();
    final var repo = this.repoRepository.findByName(name).orElseThrow();
    repo.setAllowOverride(false);
    this.repoRepository.saveAndFlush(repo);

    final var repoInfo = this.repoTxService.getRepoByName(name);
    assertThat(repoInfo.isAllowOverride()).isFalse();
    return repoInfo;
  }

  private Integer versionRows(final UUID packageId, final String version) {
    return this.jdbcTemplate.queryForObject(
        """
        select count(*) from "public"."nuget_package_version"
        where "package_id" = ? and "version" = ?
        """,
        Integer.class,
        packageId,
        version);
  }

  private void assertMetadataOf(final RepoInfo repoInfo, final String label) {
    final var info = this.packageService.findVersionInfo(repoInfo, PACKAGE_ID, VERSION);

    assertThat(info).isPresent();
    final var version = info.get();
    assertThat(version.title()).isEqualTo("Title " + label);
    assertThat(version.authors()).isEqualTo("Authors " + label);
    assertThat(version.description()).isEqualTo("Description " + label);
    assertThat(version.tags()).isEqualTo("tags-" + label);
    assertThat(version.iconUrl()).isEqualTo("https://example.test/" + label + "/icon.png");
    assertThat(version.licenseUrl()).isEqualTo("https://example.test/" + label + "/license");
    assertThat(version.projectUrl()).isEqualTo("https://example.test/" + label + "/project");
    assertThat(version.repositoryUrl()).isEqualTo("https://github.com/repsyio/" + label);
    assertThat(version.readme()).isEqualTo(readmeOf(label));
  }

  @Test
  @DisplayName("replaces the metadata and the README with the override's")
  void overrideReplacesMetadata() {
    final var repoInfo = this.seedNuGetRepo();
    final var packageId = this.createPackage(repoInfo);

    this.publishLabelled(repoInfo, packageId, "first", NO_DEPENDENCIES);
    this.assertMetadataOf(repoInfo, "first");

    this.publishLabelled(repoInfo, packageId, "second", NO_DEPENDENCIES);

    this.assertMetadataOf(repoInfo, "second");
  }

  @Test
  @DisplayName("replaces the dependencies with the override's, in the column and on the read side")
  void overrideReplacesDependencies() {
    final var repoInfo = this.seedNuGetRepo();
    final var packageId = this.createPackage(repoInfo);

    this.publishLabelled(
        repoInfo, packageId, "first", dependencyOn("Newtonsoft.Json", "[13.0.1, )", "net8.0"));
    assertThat(this.dependenciesColumn(packageId, VERSION))
        .containsEntry("json_type", "array")
        .containsEntry("json_length", 1);

    this.publishLabelled(
        repoInfo, packageId, "second", dependencyOn("Serilog", "3.1.1", ".NETStandard2.0"));

    final var column = this.dependenciesColumn(packageId, VERSION);
    assertThat(column)
        .containsEntry("json_type", "array")
        .containsEntry("json_length", 1)
        .extractingByKey("json_text")
        .asString()
        .contains("Serilog", "3.1.1", ".NETStandard2.0")
        .doesNotContain("Newtonsoft.Json");

    final var info = this.packageService.findVersionInfo(repoInfo, PACKAGE_ID, VERSION);
    assertThat(info).isPresent();
    assertThat(info.get().dependencies())
        .containsExactly(new NuGetDependencyInfo("Serilog", "3.1.1", ".NETStandard2.0"));
  }

  @Test
  @DisplayName("turns the dependencies column into SQL NULL when the override declares none")
  void overrideWithoutDependenciesClearsTheColumn() {
    final var repoInfo = this.seedNuGetRepo();
    final var packageId = this.createPackage(repoInfo);

    this.publishLabelled(
        repoInfo, packageId, "first", dependencyOn("Newtonsoft.Json", "[13.0.1, )", "net8.0"));
    assertThat(this.dependenciesColumn(packageId, VERSION)).containsEntry("json_type", "array");

    this.publishLabelled(repoInfo, packageId, "second", NO_DEPENDENCIES);

    // json_type is null for a SQL NULL, but 'null' for a jsonb null scalar and 'array' for '[]'.
    assertThat(this.dependenciesColumn(packageId, VERSION)).containsEntry("json_type", null);
    final var info = this.packageService.findVersionInfo(repoInfo, PACKAGE_ID, VERSION);
    assertThat(info).isPresent();
    assertThat(info.get().dependencies()).isNull();
    this.assertMetadataOf(repoInfo, "second");
  }

  @Test
  @DisplayName("leaves one row for the version, however often it is overridden, and no other")
  void overrideLeavesOneRow() {
    final var repoInfo = this.seedNuGetRepo();
    final var packageId = this.createPackage(repoInfo);
    this.publish(repoInfo, packageId, "2.0.0", nuspec("2.0.0", NO_DEPENDENCIES));

    this.publishLabelled(repoInfo, packageId, "first", NO_DEPENDENCIES);
    assertThat(this.versionRows(packageId, VERSION)).isEqualTo(1);

    this.publishLabelled(repoInfo, packageId, "second", NO_DEPENDENCIES);
    this.publishLabelled(repoInfo, packageId, "third", NO_DEPENDENCIES);

    assertThat(this.versionRows(packageId, VERSION)).isEqualTo(1);
    this.assertMetadataOf(repoInfo, "third");

    // Another version of the same package is not touched by the override.
    assertThat(this.versionRows(packageId, "2.0.0")).isEqualTo(1);
    assertThat(this.packageService.findVersionInfo(repoInfo, PACKAGE_ID, "2.0.0"))
        .get()
        .satisfies(v -> assertThat(v.title()).isEqualTo("Fixture Package"));
  }

  /**
   * An override is a new publish of the version: the old row is deleted and a fresh one inserted,
   * so it starts at zero downloads and is listed, even if the replaced one had been unlisted.
   * Listed is the right start for a package a publisher has just pushed again, and a count carried
   * over from different content would describe downloads of something that no longer exists. If
   * either should survive an override, this is the test to change.
   */
  @Test
  @DisplayName("starts the override at zero downloads and listed, as a new publish does")
  void overrideResetsDownloadCountAndListing() {
    final var repoInfo = this.seedNuGetRepo();
    final var packageId = this.createPackage(repoInfo);

    this.publishLabelled(repoInfo, packageId, "first", NO_DEPENDENCIES);
    // Flush and clear between the calls: incrementDownloadCount is a bulk update, so an entity
    // that unlistVersion still had in the persistence context would write the old count back.
    this.packageService.unlistVersion(repoInfo, PACKAGE_ID, VERSION);
    this.entityManager.flush();
    this.entityManager.clear();
    this.packageService.incrementDownloadCount(repoInfo, PACKAGE_ID, VERSION);
    this.packageService.incrementDownloadCount(repoInfo, PACKAGE_ID, VERSION);
    this.entityManager.flush();
    this.entityManager.clear();

    assertThat(this.packageService.findVersionInfo(repoInfo, PACKAGE_ID, VERSION))
        .get()
        .satisfies(
            v -> {
              assertThat(v.downloadCount()).isEqualTo(2);
              assertThat(v.listed()).isFalse();
            });

    this.publishLabelled(repoInfo, packageId, "second", NO_DEPENDENCIES);

    assertThat(this.packageService.findVersionInfo(repoInfo, PACKAGE_ID, VERSION))
        .get()
        .satisfies(
            v -> {
              assertThat(v.downloadCount()).isZero();
              assertThat(v.listed()).isTrue();
            });
  }

  @Test
  @DisplayName(
      "answers 409 to a republish when overrides are off and keeps the first publish's row")
  void rejectedRepublishLeavesTheRowUnchanged() {
    final var repoInfo = this.seedNuGetRepoRejectingOverride();
    final var packageId = this.createPackage(repoInfo);
    this.publishLabelled(
        repoInfo, packageId, "first", dependencyOn("Newtonsoft.Json", "[13.0.1, )", "net8.0"));

    // The second nuspec declares no dependencies, so a row that had been rewritten would lose them.
    final var filesWritten = new AtomicBoolean();
    assertThatThrownBy(
            () ->
                this.packageService.publishVersion(
                    repoInfo,
                    packageId,
                    VERSION,
                    labelledNuspec("second", NO_DEPENDENCIES),
                    readmeOf("second"),
                    replacesExisting -> {
                      filesWritten.set(true);
                      return BaseUsages.ofDisk(0);
                    }))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> {
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(e.getReason())
                  .isEqualTo("Version 1.0.0 of package fixture.package already exists.");
            });

    assertThat(filesWritten).isFalse();
    this.entityManager.clear();
    assertThat(this.versionRows(packageId, VERSION)).isEqualTo(1);
    this.assertMetadataOf(repoInfo, "first");
    assertThat(this.dependenciesColumn(packageId, VERSION))
        .containsEntry("json_type", "array")
        .containsEntry("json_length", 1)
        .extractingByKey("json_text")
        .asString()
        .contains("Newtonsoft.Json");
    assertThat(this.packageService.findVersionInfo(repoInfo, PACKAGE_ID, VERSION))
        .get()
        .satisfies(
            v ->
                assertThat(v.dependencies())
                    .containsExactly(
                        new NuGetDependencyInfo("Newtonsoft.Json", "[13.0.1, )", "net8.0")));
  }
}
