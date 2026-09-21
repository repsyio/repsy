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

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackage;
import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackageVersion;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageRepository;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageVersionRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * Full-stack coverage for the NuGet V3 search service ({@code /v3/search}) on the protocol port.
 *
 * <p>The service turned {@code skip} and {@code take} into a page number by integer division, so a
 * {@code skip} that was not a multiple of {@code take} was rounded down to the previous page
 * boundary and the client got a window that repeated rows it already had and skipped others
 * (RPS-1057). The window is now exactly the {@code take} packages that start at {@code skip}.
 *
 * <p>The version a package is found under, and the order of its version list, follow NuGet's
 * version order, not the publish order: a backport published after a newer release is not the
 * latest version (RPS-1066).
 */
@DisplayName("NuGet wire protocol search")
class NuGetSearchProtocolIT extends AbstractIntegrationTest {

  private static final String SEARCH_PATH = "/{repo}/v3/search";
  private static final int PACKAGE_COUNT = 25;

  @Autowired private NuGetPackageRepository nugetPackageRepository;
  @Autowired private NuGetPackageVersionRepository nugetPackageVersionRepository;

  /** The ids of the seeded packages in the order the search returns them (by package id). */
  private List<String> packageIds;

  private Repo repo;

  @BeforeEach
  void seedPackages() {
    this.repo = this.seedRepo(RepoType.NUGET, uniqueRepoName("nuget-search"));

    // Zero-padded, so the alphabetical order the search sorts by is also the numeric order.
    this.packageIds =
        IntStream.range(0, PACKAGE_COUNT)
            .mapToObj(i -> "search.fixture.%02d".formatted(i))
            .toList();

    this.packageIds.forEach(
        id -> {
          final var pkg = new NuGetPackage();
          pkg.setRepo(this.repo);
          pkg.setPackageId(id);
          this.nugetPackageRepository.save(pkg);
        });

    this.entityManager.flush();
  }

  private ResultActions search(final String query) throws Exception {
    final AbstractMockHttpServletRequestBuilder<?> request =
        get(SEARCH_PATH + query, this.repo.getName())
            .header(AUTHORIZATION, this.adminProtocolBearerToken());

    return this.mockMvc.perform(request.with(protocolPort()));
  }

  private static Stream<Arguments> windows() {
    return Stream.of(
        Arguments.of(0, 10, 0, 10),
        Arguments.of(10, 10, 10, 10),
        Arguments.of(5, 10, 5, 10),
        Arguments.of(15, 10, 15, 10),
        Arguments.of(7, 3, 7, 3),
        Arguments.of(1, 20, 1, 20),
        Arguments.of(20, 10, 20, 5),
        Arguments.of(23, 10, 23, 2),
        Arguments.of(24, 1, 24, 1));
  }

  @ParameterizedTest(name = "skip={0} take={1} returns {3} packages from index {2}")
  @MethodSource("windows")
  @DisplayName("returns exactly the take packages that start at skip")
  void returnsTheRequestedWindow(
      final int skip, final int take, final int firstIndex, final int expectedSize)
      throws Exception {

    final var expected = this.packageIds.subList(firstIndex, firstIndex + expectedSize);

    this.search("?q=&skip=" + skip + "&take=" + take)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalHits").value(PACKAGE_COUNT))
        .andExpect(jsonPath("$.data[*].id", contains(expected.toArray())));
  }

  @Test
  @DisplayName("serves consecutive windows that start at an offset off the take boundary")
  void consecutiveWindowsFromAnOffset() throws Exception {
    final var first = this.packageIds.subList(5, 15).toArray();
    final var second = this.packageIds.subList(15, 25).toArray();

    this.search("?q=&skip=5&take=10")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[*].id", contains(first)));
    this.search("?q=&skip=15&take=10")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[*].id", contains(second)));
  }

  @Test
  @DisplayName("answers an empty window, and the real total, past the last package")
  void pastTheEnd() throws Exception {
    this.search("?q=&skip=30&take=10")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalHits").value(PACKAGE_COUNT))
        .andExpect(jsonPath("$.data", empty()));
  }

  @Test
  @DisplayName("treats a negative skip as the start of the results")
  void negativeSkip() throws Exception {
    this.search("?q=&skip=-4&take=10")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalHits").value(PACKAGE_COUNT))
        .andExpect(jsonPath("$.data[*].id", contains(this.packageIds.subList(0, 10).toArray())));
  }

  @Test
  @DisplayName("applies the query before it applies the window")
  void filteredWindow() throws Exception {
    // "fixture.1" matches search.fixture.10 to search.fixture.19: ten packages.
    this.search("?q=fixture.1&skip=3&take=4")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalHits").value(10))
        .andExpect(jsonPath("$.data[*].id", contains(this.packageIds.subList(13, 17).toArray())));
  }

  private void seedVersions(final String packageId, final String... versionsInPublishOrder) {
    final var pkg = new NuGetPackage();
    pkg.setRepo(this.repo);
    pkg.setPackageId(packageId);
    final var saved = this.nugetPackageRepository.save(pkg);

    final var start = Instant.parse("2026-01-01T00:00:00Z");
    for (int i = 0; i < versionsInPublishOrder.length; i++) {
      final var version = versionsInPublishOrder[i];
      final var row = new NuGetPackageVersion();
      row.setNugetPackage(saved);
      row.setVersion(version);
      row.setPrerelease(version.contains("-"));
      row.setListed(true);
      row.setPublishedAt(start.plusSeconds(i * 3600L));
      row.setDownloadCount(i);
      row.setCreatedAt(start.plusSeconds(i * 3600L));
      this.nugetPackageVersionRepository.save(row);
    }

    this.entityManager.flush();
  }

  @Test
  @DisplayName("reports the highest version as the latest, not the one published last")
  void latestIsTheHighestVersion() throws Exception {
    // 1.0.5 is a backport, published after 2.0.0.
    this.seedVersions("search.versions", "1.0.0", "2.0.0", "1.0.5");

    this.search("?q=search.versions&take=10")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalHits").value(1))
        .andExpect(jsonPath("$.data[*].version", contains("2.0.0")))
        .andExpect(jsonPath("$.data[0].versions[*].version", contains("2.0.0", "1.0.5", "1.0.0")));
  }

  @Test
  @DisplayName("leaves a pre-release out unless it is asked for, and then ranks it by its version")
  void preReleaseFollowsTheVersionOrder() throws Exception {
    this.seedVersions("search.versions", "1.0.0", "3.0.0-beta", "1.1.0");

    this.search("?q=search.versions&take=10")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[*].version", contains("1.1.0")))
        .andExpect(jsonPath("$.data[0].versions[*].version", contains("1.1.0", "1.0.0")));

    this.search("?q=search.versions&take=10&prerelease=true")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[*].version", contains("3.0.0-beta")))
        .andExpect(
            jsonPath("$.data[0].versions[*].version", contains("3.0.0-beta", "1.1.0", "1.0.0")));
  }
}
