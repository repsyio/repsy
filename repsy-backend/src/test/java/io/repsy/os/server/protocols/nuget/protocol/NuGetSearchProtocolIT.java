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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackage;
import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackageVersion;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageRepository;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageVersionRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.nuget.protocol.handlers.AbstractNuGetAutocompleteProtocolMethodHandler;
import io.repsy.protocols.nuget.protocol.handlers.AbstractNuGetSearchProtocolMethodHandler;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
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
 *
 * <p>{@code skip} and {@code take} used to be parsed with {@code Integer.parseInt} inside a
 * catch-all: a malformed value answered 500 with an ERROR stack trace instead of 400, and {@code
 * take} had no upper bound, so a client could read a whole repo's package listing in one request
 * (RPS-1120). {@link ParameterValidation} pins the fix for both {@code /v3/search} and {@code
 * /v3/autocomplete}.
 */
@DisplayName("NuGet wire protocol search")
class NuGetSearchProtocolIT extends AbstractIntegrationTest {

  private static final String SEARCH_PATH = "/{repo}/v3/search";
  private static final String AUTOCOMPLETE_PATH = "/{repo}/v3/autocomplete";
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
    return this.request(SEARCH_PATH, query);
  }

  private ResultActions request(final String path, final String query) throws Exception {
    final AbstractMockHttpServletRequestBuilder<?> request =
        get(path + query, this.repo.getName())
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
  @DisplayName("answers 400 for a negative skip instead of silently clamping it (RPS-1120)")
  void negativeSkip() throws Exception {
    this.search("?q=&skip=-4&take=10").andExpect(status().isBadRequest());
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

  private static Stream<Arguments> endpoints() {
    return Stream.of(Arguments.of(SEARCH_PATH), Arguments.of(AUTOCOMPLETE_PATH));
  }

  /**
   * RPS-1120: {@code skip} and {@code take} were parsed with {@code Integer.parseInt} inside a
   * catch-all, so a malformed value answered 500 with an ERROR stack trace, and {@code take} had no
   * upper bound. Covers both {@code /v3/search} and {@code /v3/autocomplete}, since both handlers
   * shared the same bug.
   */
  @Nested
  @DisplayName("skip and take validation")
  class ParameterValidation {

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger searchLogger =
        (Logger) LoggerFactory.getLogger(AbstractNuGetSearchProtocolMethodHandler.class);
    private final Logger autocompleteLogger =
        (Logger) LoggerFactory.getLogger(AbstractNuGetAutocompleteProtocolMethodHandler.class);

    @BeforeEach
    void captureLogs() {
      this.logs.start();
      this.searchLogger.addAppender(this.logs);
      this.autocompleteLogger.addAppender(this.logs);
    }

    @AfterEach
    void stopCapturingLogs() {
      this.searchLogger.detachAppender(this.logs);
      this.autocompleteLogger.detachAppender(this.logs);
    }

    @ParameterizedTest(name = "{0}?skip=abc")
    @MethodSource("io.repsy.os.server.protocols.nuget.protocol.NuGetSearchProtocolIT#endpoints")
    @DisplayName("answers 400 for a non-numeric skip, without an ERROR log")
    void nonNumericSkip(final String path) throws Exception {
      NuGetSearchProtocolIT.this
          .request(path, "?q=&skip=abc&take=10")
          .andExpect(status().isBadRequest());
      this.assertNoErrorLogged();
    }

    @ParameterizedTest(name = "{0}?take=1.5")
    @MethodSource("io.repsy.os.server.protocols.nuget.protocol.NuGetSearchProtocolIT#endpoints")
    @DisplayName("answers 400 for a decimal take, without an ERROR log")
    void decimalTake(final String path) throws Exception {
      NuGetSearchProtocolIT.this
          .request(path, "?q=&skip=0&take=1.5")
          .andExpect(status().isBadRequest());
      this.assertNoErrorLogged();
    }

    @ParameterizedTest(name = "{0}?take=99999999999")
    @MethodSource("io.repsy.os.server.protocols.nuget.protocol.NuGetSearchProtocolIT#endpoints")
    @DisplayName("answers 400 for a take that overflows int, without an ERROR log")
    void overflowingTake(final String path) throws Exception {
      NuGetSearchProtocolIT.this
          .request(path, "?q=&skip=0&take=99999999999")
          .andExpect(status().isBadRequest());
      this.assertNoErrorLogged();
    }

    @ParameterizedTest(name = "{0}?take=-1")
    @MethodSource("io.repsy.os.server.protocols.nuget.protocol.NuGetSearchProtocolIT#endpoints")
    @DisplayName("answers 400 for a negative take, without an ERROR log")
    void negativeTake(final String path) throws Exception {
      NuGetSearchProtocolIT.this
          .request(path, "?q=&skip=0&take=-1")
          .andExpect(status().isBadRequest());
      this.assertNoErrorLogged();
    }

    @ParameterizedTest(name = "{0}?take=2000000000")
    @MethodSource("io.repsy.os.server.protocols.nuget.protocol.NuGetSearchProtocolIT#endpoints")
    @DisplayName("clamps a huge valid take instead of answering an error")
    void hugeTakeIsClampedNotRejected(final String path) throws Exception {
      NuGetSearchProtocolIT.this
          .request(path, "?q=&skip=0&take=2000000000")
          .andExpect(status().isOk());
      this.assertNoErrorLogged();
    }

    @Test
    @DisplayName(
        "autocomplete answers 400, not 500, when skip + take overflows int even though take is"
            + " clamped and both values are individually valid")
    void autocompleteSkipPlusTakeOverflow() throws Exception {
      // take is clamped to 1000 before it reaches the service, so a huge take alone cannot
      // overflow skip + take there. skip is not capped, so a skip near Integer.MAX_VALUE still
      // can, combined with any take above 0.
      NuGetSearchProtocolIT.this
          .request(AUTOCOMPLETE_PATH, "?q=&skip=2147483647&take=1")
          .andExpect(status().isBadRequest());
      this.assertNoErrorLogged();
    }

    private void assertNoErrorLogged() {
      Assertions.assertThat(this.logs.list).noneMatch(event -> event.getLevel() == Level.ERROR);
    }
  }
}
