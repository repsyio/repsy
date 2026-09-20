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
package io.repsy.os.server.protocols;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.H2IntegrationTest;
import io.repsy.os.server.protocols.cargo.shared.crate.entities.CargoCrateIndex;
import io.repsy.os.server.protocols.cargo.shared.crate.repositories.CargoCrateIndexRepository;
import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackage;
import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackageVersion;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageRepository;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageVersionRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.protocols.cargo.shared.crate.dtos.CratePublishRequest;
import io.repsy.protocols.cargo.shared.crate.services.CargoCrateService;
import io.repsy.protocols.nuget.shared.packages.services.NuGetPackageService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Regression coverage for JSON-typed entity fields on H2's CLOB columns (RPS-957). */
@DisplayName("JSON persistence on embedded H2")
class H2JsonPersistenceIT extends H2IntegrationTest {

  @Autowired private RepoRepository repoRepository;
  @Autowired private NuGetPackageRepository nuGetPackageRepository;
  @Autowired private NuGetPackageVersionRepository nuGetPackageVersionRepository;
  @Autowired private NuGetPackageService<UUID> nuGetPackageService;
  @Autowired private CargoCrateService<UUID> cargoCrateService;
  @Autowired private CargoCrateIndexRepository cargoCrateIndexRepository;

  @Test
  @DisplayName("publishes and reads NuGet dependency JSON")
  void publishesNuGetDependenciesOnH2() throws IOException {
    final var repo = this.repo(RepoType.NUGET, "h2nuget");
    final var nugetPackage = new NuGetPackage();
    nugetPackage.setRepo(repo);
    nugetPackage.setPackageId("h2.fixture");
    final var packageId = this.nuGetPackageRepository.save(nugetPackage).getId();

    this.nuGetPackageService.publishVersion(
        this.repoInfo(repo),
        packageId,
        "1.0.0",
        """
        <package><metadata><id>h2.fixture</id><version>1.0.0</version>
        <dependencies><group targetFramework="net8.0">
        <dependency id="Newtonsoft.Json" version="13.0.3" />
        </group></dependencies></metadata></package>
        """,
        null,
        replacesExisting -> BaseUsages.ofDisk(0));

    this.nuGetPackageVersionRepository.flush();
    final NuGetPackageVersion stored =
        this.nuGetPackageVersionRepository
            .findByNugetPackageIdAndVersion(packageId, "1.0.0")
            .orElseThrow();

    assertThat(stored.getDependencies()).contains("Newtonsoft.Json");
    assertThat(this.nuGetPackageService.findVersionInfo(this.repoInfo(repo), "h2.fixture", "1.0.0"))
        .isPresent();
  }

  @Test
  @DisplayName("publishes and reads Cargo dependency and feature JSON")
  void publishesCargoJsonOnH2() {
    final var repo = this.repo(RepoType.CARGO, "h2cargo");
    final var info = this.repoInfo(repo);
    final var request =
        new CratePublishRequest(
            "h2-crate",
            "1.0.0",
            true,
            List.of(),
            Map.of("default", List.of("serde")),
            List.of("Repsy"),
            "fixture",
            null,
            null,
            "readme",
            null,
            List.of(),
            List.of(),
            "MIT",
            null,
            null,
            null,
            null,
            "checksum",
            Map.of("serde", List.of("derive")));

    this.cargoCrateService.publish(info, request);
    this.cargoCrateIndexRepository.flush();
    final CargoCrateIndex stored = this.cargoCrateIndexRepository.findAll().getFirst();

    assertThat(stored.getDeps()).isEqualTo("[]");
    assertThat(stored.getFeatures()).contains("default");
    assertThat(stored.getFeatures2()).contains("serde");
    assertThat(this.cargoCrateService.getIndexEntries(info, "h2-crate")).hasSize(1);
  }

  private Repo repo(final RepoType type, final String name) {
    final var repo = new Repo();
    repo.setName(name);
    repo.setType(type);
    repo.setPrivateRepo(false);
    repo.setAllowOverride(true);
    repo.setSearchable(true);
    repo.setDiskUsage(0);
    repo.setCreatedAt(Instant.now());
    return this.repoRepository.saveAndFlush(repo);
  }

  private BaseRepoInfo<UUID> repoInfo(final Repo repo) {
    return BaseRepoInfo.<UUID>builder()
        .id(repo.getId())
        .name(repo.getName())
        .type(repo.getType())
        .privateRepo(repo.isPrivateRepo())
        .allowOverride(repo.isAllowOverride())
        .searchable(repo.isSearchable())
        .diskUsage(repo.getDiskUsage())
        .build();
  }
}
