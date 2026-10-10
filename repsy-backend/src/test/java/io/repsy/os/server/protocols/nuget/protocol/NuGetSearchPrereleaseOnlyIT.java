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

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIT;
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
 * RPS-2105: the NuGet V3 search without {@code prerelease=true} leaves pre-release versions out,
 * and a package that has only pre-release versions is not a result at all, in the hit list and in
 * {@code totalHits}.
 */
@DisplayName("NuGet wire protocol search of pre-release-only packages")
class NuGetSearchPrereleaseOnlyIT extends AbstractIT {

  private static final String SEARCH_PATH = "/{repo}/v3/search";
  private static final String AUTOCOMPLETE_PATH = "/{repo}/v3/autocomplete";

  @Autowired private NuGetPackageRepository nugetPackageRepository;
  @Autowired private NuGetPackageVersionRepository nugetPackageVersionRepository;

  private Repo repo;

  @BeforeEach
  void seed() {
    this.repo = this.seedRepo(RepoType.NUGET, uniqueRepoName("nuget-prerelease"));

    this.seedVersions("pre.a.only", "1.0.0-beta", "2.0.0-rc.1");
    this.seedVersions("pre.b.stable", "1.0.0");
    this.seedVersions("pre.c.mixed", "1.0.0-beta", "1.0.0");
    this.seedVersions("pre.d.only", "0.1.0-alpha");
    this.entityManager.flush();
  }

  private void seedVersions(final String packageId, final String... versions) {
    final var pkg = new NuGetPackage();
    pkg.setRepo(this.repo);
    pkg.setPackageId(packageId);
    final var saved = this.nugetPackageRepository.save(pkg);

    final var start = Instant.parse("2026-01-01T00:00:00Z");
    for (int i = 0; i < versions.length; i++) {
      final var row = new NuGetPackageVersion();
      row.setNugetPackage(saved);
      row.setVersion(versions[i]);
      row.setPrerelease(versions[i].contains("-"));
      row.setListed(true);
      row.setPublishedAt(start.plusSeconds(i * 3600L));
      row.setCreatedAt(start.plusSeconds(i * 3600L));
      this.nugetPackageVersionRepository.save(row);
    }
  }

  private ResultActions request(final String path, final String query) throws Exception {
    return this.mockMvc.perform(
        get(path + query, this.repo.getName())
            .header(AUTHORIZATION, this.adminProtocolBearerToken())
            .with(protocolPort()));
  }

  @Test
  @DisplayName("does not list a package with only pre-release versions by default")
  void preReleaseOnlyPackageIsNotListed() throws Exception {
    this.request(SEARCH_PATH, "?q=pre.&take=10")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalHits").value(2))
        .andExpect(jsonPath("$.data[*].id", contains("pre.b.stable", "pre.c.mixed")))
        .andExpect(jsonPath("$.data[1].versions[*].version", contains("1.0.0")));
  }

  @Test
  @DisplayName("lists a pre-release-only package with prerelease=true")
  void preReleaseOnlyPackageIsListedWhenAskedFor() throws Exception {
    this.request(SEARCH_PATH, "?q=pre.&take=10&prerelease=true")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalHits").value(4))
        .andExpect(
            jsonPath(
                "$.data[*].id",
                contains("pre.a.only", "pre.b.stable", "pre.c.mixed", "pre.d.only")));
  }

  @Test
  @DisplayName("pages over the packages that are results, not over the hidden ones")
  void windowSkipsHiddenPackages() throws Exception {
    this.request(SEARCH_PATH, "?q=pre.&skip=1&take=1")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalHits").value(2))
        .andExpect(jsonPath("$.data[*].id", contains("pre.c.mixed")));
    this.request(SEARCH_PATH, "?q=pre.only&take=10")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalHits").value(0))
        .andExpect(jsonPath("$.data", empty()));
  }

  @Test
  @DisplayName("autocompletes only the package ids that have a stable version by default")
  void autocompleteLeavesPreReleaseOnlyPackagesOut() throws Exception {
    this.request(AUTOCOMPLETE_PATH, "?q=pre.&take=10")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data", contains("pre.b.stable", "pre.c.mixed")));
    this.request(AUTOCOMPLETE_PATH, "?q=pre.&take=10&prerelease=true")
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                "$.data", contains("pre.a.only", "pre.b.stable", "pre.c.mixed", "pre.d.only")));
  }
}
