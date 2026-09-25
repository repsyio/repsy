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
package io.repsy.os.server.protocols.nuget.protocol;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackage;
import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackageVersion;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageRepository;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageVersionRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Base of the suites that pin where the NuGet service index, the registration and the search take
 * the address they name their URLs with (RPS-1432). A subclass says what that address is; the
 * request always comes from {@code internal.example:9090}, an address a client behind a reverse
 * proxy cannot reach.
 */
abstract class AbstractNuGetBaseUrlIT extends AbstractIntegrationTest {

  private static final String PACKAGE_ID = "Base.Url.Package";
  private static final String PACKAGE_ID_LOWER = "base.url.package";
  private static final String VERSION = "1.2.3";

  @Autowired private NuGetPackageRepository nugetPackageRepository;
  @Autowired private NuGetPackageVersionRepository nugetPackageVersionRepository;

  private Repo repo;

  /** The address the URLs of the repo start with, without a trailing slash. */
  abstract String expectedRepoUrl(String repoName);

  @BeforeEach
  void seedPackage() {
    this.repo = this.seedRepo(RepoType.NUGET, uniqueRepoName("nuget-base-url"));

    final var pkg = new NuGetPackage();
    pkg.setRepo(this.repo);
    pkg.setPackageId(PACKAGE_ID);
    final var saved = this.nugetPackageRepository.save(pkg);

    final var row = new NuGetPackageVersion();
    row.setNugetPackage(saved);
    row.setVersion(VERSION);
    row.setPrerelease(false);
    row.setListed(true);
    row.setPublishedAt(Instant.parse("2026-01-01T00:00:00Z"));
    row.setDownloadCount(0);
    row.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
    this.nugetPackageVersionRepository.save(row);

    this.entityManager.flush();
  }

  private ResultActions read(final String pathAfterRepo) throws Exception {
    return this.mockMvc.perform(
        get("/{repo}" + pathAfterRepo, this.repo.getName())
            .header(AUTHORIZATION, this.adminProtocolBearerToken())
            .with(protocolPort())
            .with(
                request -> {
                  request.setServerName("internal.example");
                  request.setServerPort(9090);
                  return request;
                }));
  }

  private String repoUrl() {
    return this.expectedRepoUrl(this.repo.getName());
  }

  @Test
  @DisplayName("the service index names every resource under the address")
  void serviceIndex() throws Exception {
    this.read("/v3/index.json")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resources[*].@id", everyItem(startsWith(this.repoUrl() + "/v3/"))));
  }

  @Test
  @DisplayName("the registration index names its leaves and the .nupkg under the address")
  void registrationIndex() throws Exception {
    final var registration = this.repoUrl() + "/v3/registration/" + PACKAGE_ID_LOWER;
    final var leaf = registration + "/" + VERSION + ".json";
    final var nupkg =
        this.repoUrl()
            + "/v3/package/"
            + PACKAGE_ID_LOWER
            + "/"
            + VERSION
            + "/"
            + PACKAGE_ID_LOWER
            + "."
            + VERSION
            + ".nupkg";

    this.read("/v3/registration/" + PACKAGE_ID_LOWER + "/index.json")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.@id").value(registration + "/index.json"))
        .andExpect(jsonPath("$.items[0].items[0].@id").value(leaf))
        .andExpect(jsonPath("$.items[0].items[0].packageContent").value(nupkg));
  }

  @Test
  @DisplayName("a registration leaf names itself and the .nupkg under the address")
  void registrationLeaf() throws Exception {
    final var registration = this.repoUrl() + "/v3/registration/" + PACKAGE_ID_LOWER;
    final var nupkg =
        this.repoUrl()
            + "/v3/package/"
            + PACKAGE_ID_LOWER
            + "/"
            + VERSION
            + "/"
            + PACKAGE_ID_LOWER
            + "."
            + VERSION
            + ".nupkg";

    this.read("/v3/registration/" + PACKAGE_ID_LOWER + "/" + VERSION + ".json")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.@id").value(registration + "/" + VERSION + ".json"))
        .andExpect(jsonPath("$.packageContent").value(nupkg));
  }

  @Test
  @DisplayName("a search result points at its registration under the address")
  void search() throws Exception {
    this.read("/v3/search?q=" + PACKAGE_ID_LOWER)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalHits").value(1))
        .andExpect(
            jsonPath("$.data[0].registration")
                .value(this.repoUrl() + "/v3/registration/" + PACKAGE_ID_LOWER + "/index.json"));
  }
}
