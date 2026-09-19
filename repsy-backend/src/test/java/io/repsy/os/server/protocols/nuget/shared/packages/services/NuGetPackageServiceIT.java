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
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageRepository;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetDependencyInfo;
import io.repsy.protocols.nuget.shared.packages.services.NuGetPackageService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Regression coverage for persisting NuGet version metadata on PostgreSQL.
 *
 * <p>{@code nuget_package_version.dependencies} is a {@code jsonb} column. Mapping it as a plain
 * {@code String} makes Hibernate bind {@code VARCHAR}, which PostgreSQL rejects with SQLState
 * 42804, so publishing any package that declares a dependency failed. These tests publish through
 * {@link NuGetPackageService#publishVersion} - the path the protocol handlers use - and check both
 * what lands in the column and what the read side returns.
 */
@DisplayName("NuGetPackageServiceImpl publish/read on PostgreSQL")
class NuGetPackageServiceIT extends AbstractIntegrationTest {

  private static final String PACKAGE_ID = "fixture.package";

  @Autowired private NuGetPackageService<UUID> packageService;
  @Autowired private NuGetPackageRepository packageRepository;
  @Autowired private RepoTxService repoTxService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private RepoInfo seedNuGetRepo() {
    final var repo = this.seedRepo(RepoType.NUGET, uniqueRepoName("nuget"));
    return this.repoTxService.getRepoByName(repo.getName());
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
    this.packageService.publishVersion(repoInfo, packageId, version, nuspec);
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
}
