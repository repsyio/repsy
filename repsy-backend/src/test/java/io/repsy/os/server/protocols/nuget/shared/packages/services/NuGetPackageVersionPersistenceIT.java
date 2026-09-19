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
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetDependencyInfo;
import io.repsy.protocols.nuget.shared.packages.services.NuGetPackageService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Regression coverage for persisting NuGet dependency metadata into the PostgreSQL {@code jsonb}
 * {@code nuget_package_version.dependencies} column (RPS-901).
 */
@DisplayName("NuGet package version dependency persistence")
class NuGetPackageVersionPersistenceIT extends AbstractIntegrationTest {

  private static final String NUSPEC_WITH_DEPENDENCIES =
      """
      <package><metadata>
        <id>%s</id>
        <version>%s</version>
        <dependencies>
          <group targetFramework="net8.0">
            <dependency id="Newtonsoft.Json" version="13.0.3" />
            <dependency id="Serilog" version="[3.0.0, 4.0.0)" />
          </group>
        </dependencies>
      </metadata></package>
      """;

  private static final String NUSPEC_WITHOUT_DEPENDENCIES =
      "<package><metadata><id>%s</id><version>%s</version></metadata></package>";

  @Autowired private NuGetPackageService<UUID> nugetPackageService;
  @Autowired private NuGetPackageRepository nugetPackageRepository;
  @Autowired private RepoTxService repoTxService;
  @Autowired private JdbcTemplate jdbcTemplate;

  /**
   * Saves the package inside the test transaction. {@code findOrCreatePackage} inserts in a {@code
   * REQUIRES_NEW} transaction that cannot see the repo row this test has not committed.
   */
  private UUID createPackage(final UUID repoId, final String packageId) {
    final var pkg = new NuGetPackage();
    pkg.setRepo(this.entityManager.getReference(Repo.class, repoId));
    pkg.setPackageId(packageId.toLowerCase(Locale.ROOT));
    return this.nugetPackageRepository.save(pkg).getId();
  }

  @Test
  @DisplayName("stores dependencies as a jsonb array and reads them back")
  void storesDependenciesAsJsonbArray() {
    final var repoName = uniqueRepoName("nugetdeps");
    this.seedRepo(RepoType.NUGET, repoName);
    final var repoInfo = this.repoTxService.getRepoByName(repoName);

    final var packageId = "Repsy.Deps.Fixture";
    final var pkgId = this.createPackage(repoInfo.getId(), packageId);

    this.nugetPackageService.publishVersion(
        repoInfo, pkgId, "1.0.0", NUSPEC_WITH_DEPENDENCIES.formatted(packageId, "1.0.0"), null);
    this.entityManager.flush();
    this.entityManager.clear();

    final var storedType =
        this.jdbcTemplate.queryForObject(
            "select jsonb_typeof(dependencies) from nuget_package_version where package_id = ?",
            String.class,
            pkgId);
    assertThat(storedType).isEqualTo("array");

    final var info =
        this.nugetPackageService.findVersionInfo(repoInfo, packageId, "1.0.0").orElseThrow();
    assertThat(info.dependencies())
        .containsExactlyInAnyOrder(
            new NuGetDependencyInfo("Newtonsoft.Json", "13.0.3", "net8.0"),
            new NuGetDependencyInfo("Serilog", "[3.0.0, 4.0.0)", "net8.0"));
  }

  @Test
  @DisplayName("stores a version without dependencies as SQL null")
  void storesNoDependenciesAsNull() {
    final var repoName = uniqueRepoName("nugetnodeps");
    this.seedRepo(RepoType.NUGET, repoName);
    final var repoInfo = this.repoTxService.getRepoByName(repoName);

    final var packageId = "Repsy.NoDeps.Fixture";
    final var pkgId = this.createPackage(repoInfo.getId(), packageId);

    this.nugetPackageService.publishVersion(
        repoInfo, pkgId, "1.0.0", NUSPEC_WITHOUT_DEPENDENCIES.formatted(packageId, "1.0.0"), null);
    this.entityManager.flush();
    this.entityManager.clear();

    final var stored =
        this.jdbcTemplate.queryForList(
            "select dependencies from nuget_package_version where package_id = ?", pkgId);
    assertThat(stored)
        .singleElement()
        .satisfies(row -> assertThat(row.get("dependencies")).isNull());

    final var info =
        this.nugetPackageService.findVersionInfo(repoInfo, packageId, "1.0.0").orElseThrow();
    assertThat(info.dependencies()).isNullOrEmpty();
  }
}
