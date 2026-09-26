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
package io.repsy.os.shared.usage.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Full-stack integration tests for {@code GET /api/usages}, exercising the real Spring context, MVC
 * dispatch and a containerized PostgreSQL database (Flyway-migrated) end to end.
 *
 * <p>Follows the pattern established by {@code ProfileControllerIT} and {@code UserControllerIT}:
 * requests go through {@link MockMvc}, but {@code UsageController} is only registered on the "api"
 * multiport connector, so every request is routed through {@link #apiPort()} to fake the local port
 * onto {@code multiport.ports.api} (8080).
 *
 * <p>Unlike those classes, this one is <em>not</em> transactional. {@code
 * UsageUpdateService#updateUsage} is {@code @Async}: it runs on another thread in its own
 * transaction, so it can only see repositories that have already been committed. Every fixture is
 * therefore committed for real, and {@link #cleanUp()} removes exactly what the test created.
 *
 * <p>The database is shared with every other IT class and {@code TotalUsageInfo} is a global
 * aggregate over the {@code repo} table, which is not empty: on startup {@code
 * AdminUserInitializer} seeds the {@code admin} user and publishes a {@code UserCreatedEvent},
 * whose {@code @Async} per-protocol listeners create one default zero-usage repository per {@link
 * RepoType}. {@link #measureBaseline()} records what the table holds before each test and {@link
 * #assertTotals} adds the test's own repositories and usage on top, so no test needs the table to
 * be empty. Tests must not run in parallel with each other.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("UsageController GET /api/usages")
class UsageControllerIT extends AbstractIntegrationTest {

  private static final String USAGES_PATH = "/api/usages";
  private static final String UNAUTHORIZED_TEXT =
      "Please log in: the credentials are missing or invalid, or the account is gone.";
  private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(10);

  private static final String[] TOTAL_USAGE_KEYS = {"diskUsed", "reposCount"};
  private static final String[] USAGE_INFO_KEYS = {"value", "text"};

  @Autowired private RepoTxService repoTxService;
  @Autowired private UsageUpdateService usageUpdateService;
  @Autowired private ApplicationEventPublisher eventPublisher;

  private final List<UUID> createdUserIds = new ArrayList<>();
  private final List<UUID> createdRepoIds = new ArrayList<>();

  /**
   * What the {@code repo} table held before the test: the startup-seeded default repositories. The
   * database is shared with every other IT class, so the totals are asserted as this baseline plus
   * what the test created.
   */
  private long baselineRepos;

  private long baselineDiskUsage;

  @BeforeEach
  void measureBaseline() {
    final var totalDiskUsage = this.repoRepository.getTotalDiskUsage();

    this.baselineRepos = this.repoRepository.count();
    this.baselineDiskUsage = totalDiskUsage == null ? 0 : totalDiskUsage;

    // The totals text is pinned per absolute size, which only holds while the repositories already
    // in the table (the zero-usage defaults) add nothing to it.
    assertThat(this.baselineDiskUsage)
        .as("disk usage of the repositories that exist before the test")
        .isZero();
  }

  @AfterEach
  void cleanUp() {
    this.repoRepository.deleteAllById(this.createdRepoIds);
    this.userRepository.deleteAllById(this.createdUserIds);
  }

  // ---------------------------------------------------------------------------------------------
  // Request / fixture helpers
  // ---------------------------------------------------------------------------------------------

  private ResultActions getUsages(final String authorization) throws Exception {
    return this.perform(get(USAGES_PATH).header(AUTHORIZATION, authorization));
  }

  /** Creates a committed user with the given role and returns its username. */
  private String createUser(final UserRole role) {
    final var username = uniqueUsername("usage");
    final var hash = VALID_PASSWORD_HASH;
    final var userInfo = this.userTxService.create(username, role, hash);
    this.createdUserIds.add(userInfo.getId());
    return username;
  }

  private String bearerTokenFor(final String username) {
    final var userId = this.userRepository.findByUsername(username).orElseThrow().getId();
    return this.bearerTokenFor(userId, username);
  }

  private String expiredBearerTokenFor(final String username) {
    final var userId = this.userRepository.findByUsername(username).orElseThrow().getId();
    return AuthUtils.AUTH_BEARER
        + this.jwtUtils.createPanelAccessToken(userId, username, Duration.ofSeconds(-30));
  }

  /** A valid bearer token for the {@code admin} user that the application seeds at startup. */
  private String seededAdminBearerToken() {
    return this.bearerTokenFor(SEEDED_ADMIN_USERNAME);
  }

  private UUID createRepo(final RepoType type, final boolean privateRepo) {
    final var name = "u" + randomTag() + "-" + type.name().toLowerCase(Locale.ROOT);
    final var repoId = this.repoTxService.createRepo(name, type, privateRepo, null).getId();
    this.createdRepoIds.add(repoId);
    return repoId;
  }

  private UUID createRepo(final RepoType type) {
    return this.createRepo(type, false);
  }

  /**
   * Records a usage change through the real {@code @Async} service and waits until it has been
   * applied, so the next request observes it.
   */
  private void recordUsage(final UUID repoId, final long diskUsageDiff) {
    final var expected = this.diskUsageOf(repoId) + diskUsageDiff;

    this.usageUpdateService.updateUsage(
        new UsageChangedInfo(repoId, BaseUsages.ofDisk(diskUsageDiff)));

    await()
        .atMost(ASYNC_TIMEOUT)
        .untilAsserted(() -> assertThat(this.diskUsageOf(repoId)).isEqualTo(expected));
  }

  private long diskUsageOf(final UUID repoId) {
    return this.repoRepository.findById(repoId).orElseThrow().getDiskUsage();
  }

  // ---------------------------------------------------------------------------------------------
  // Response helpers
  // ---------------------------------------------------------------------------------------------

  /**
   * Asserts a 200 SUCCESS envelope (exact key set, {@code errorCode} null, {@code text} resolved
   * from the {@code usageFetched} entry in messages.properties) and returns the raw body for
   * further assertions on {@code data}.
   */
  private static String expectSuccess(final ResultActions result) throws Exception {
    return expectSuccess(result, "usageFetched", "Usage fetched");
  }

  /**
   * Asserts the complete {@code TotalUsageInfo} shape: exact key sets and every value. {@code
   * diskUsed} and {@code reposCount} are what the test itself added on top of {@link
   * #baselineDiskUsage} and {@link #baselineRepos}.
   */
  private void assertTotals(
      final String body, final long diskUsed, final String diskUsedText, final long reposCount) {
    final Map<String, Object> data = JsonPath.read(body, "$.data");
    assertThat(data).containsOnlyKeys(TOTAL_USAGE_KEYS);
    assertThat(number(data.get("reposCount"))).isEqualTo(this.baselineRepos + reposCount);

    final Map<String, Object> usageInfo = JsonPath.read(body, "$.data.diskUsed");
    assertThat(usageInfo).containsOnlyKeys(USAGE_INFO_KEYS).containsEntry("text", diskUsedText);
    assertThat(number(usageInfo.get("value"))).isEqualTo(this.baselineDiskUsage + diskUsed);
  }

  private static void expectAccessNotAllowed(final ResultActions result) throws Exception {
    expectError(
        result,
        HttpStatus.UNAUTHORIZED,
        "accessNotAllowed",
        "accessNotAllowed",
        "Access isn't allowed.");
  }

  private static long number(final Object json) {
    return ((Number) json).longValue();
  }

  // ---------------------------------------------------------------------------------------------
  // Authentication & authorization
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("authentication & authorization")
  class Security {

    @Test
    @DisplayName("returns 401 when the Authorization header is missing")
    void missingAuthorizationHeader() throws Exception {
      expectError(
          UsageControllerIT.this.perform(get(USAGES_PATH)),
          HttpStatus.UNAUTHORIZED,
          "missingRequestHeader",
          "Authorization",
          "A required request header is missing.");
    }

    @Test
    @DisplayName("returns 401 for a header without a Bearer prefix")
    void nonBearerAuthorizationHeader() throws Exception {
      expectAccessNotAllowed(UsageControllerIT.this.getUsages("Basic dXNlcjpw"));
    }

    @Test
    @DisplayName("returns 401 for a malformed/garbage bearer token")
    void malformedBearerToken() throws Exception {
      expectAccessNotAllowed(UsageControllerIT.this.getUsages("Bearer not-a-jwt"));
    }

    @Test
    @DisplayName("returns 401 for an expired token")
    void expiredToken() throws Exception {
      final var token = UsageControllerIT.this.expiredBearerTokenFor(SEEDED_ADMIN_USERNAME);

      expectError(
          UsageControllerIT.this.getUsages(token),
          HttpStatus.UNAUTHORIZED,
          "sessionExpired",
          "sessionExpired",
          "Session expired.");
    }

    @Test
    @DisplayName("returns 401 unAuthorized when the token's user never existed")
    void tokenUserNeverExisted() throws Exception {
      // Authentication resolves the caller by the token's username claim, not by its subject id.
      final var token =
          UsageControllerIT.this.bearerTokenFor(UUID.randomUUID(), "ghost" + randomTag());

      expectError(
          UsageControllerIT.this.getUsages(token),
          HttpStatus.UNAUTHORIZED,
          "loginRequired",
          "unAuthorized",
          UNAUTHORIZED_TEXT);
    }

    @Test
    @DisplayName(
        "returns 401 unAuthorized once the token's user has been deleted, though the token is valid")
    void tokenUserDeleted() throws Exception {
      final var username = UsageControllerIT.this.createUser(UserRole.ADMIN);
      final var token = UsageControllerIT.this.bearerTokenFor(username);
      expectSuccess(UsageControllerIT.this.getUsages(token));

      UsageControllerIT.this.userRepository.delete(
          UsageControllerIT.this.userRepository.findByUsername(username).orElseThrow());

      expectError(
          UsageControllerIT.this.getUsages(token),
          HttpStatus.UNAUTHORIZED,
          "loginRequired",
          "unAuthorized",
          UNAUTHORIZED_TEXT);
    }

    /**
     * Intended policy (RPS-876): the controller only calls {@code authenticate}, never {@code
     * requireAdmin}, so any signed-in user sees the instance-wide totals. The dashboard, which
     * every signed-in user lands on, renders them in its Total Disk card, so restricting the
     * endpoint would break the non-admin dashboard. Unlike {@code /api/users}, which answers a
     * non-admin with 403 {@code accessDenied}, this endpoint is meant to stay open.
     */
    @Test
    @DisplayName("lets a non-admin user read the totals (open to any authenticated user)")
    void nonAdminCaller() throws Exception {
      final var repoId = UsageControllerIT.this.createRepo(RepoType.MAVEN);
      UsageControllerIT.this.recordUsage(repoId, 2048);
      final var username = UsageControllerIT.this.createUser(UserRole.USER);
      final var token = UsageControllerIT.this.bearerTokenFor(username);

      final var body = expectSuccess(UsageControllerIT.this.getUsages(token));

      assertTotals(body, 2048, "2.00 KB", 1);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Happy paths
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("totals")
  class Totals {

    @Test
    @DisplayName("without a repository of its own reports the baseline: zero usage")
    void noRepositories() throws Exception {
      final var body =
          expectSuccess(
              UsageControllerIT.this.getUsages(UsageControllerIT.this.seededAdminBearerToken()));

      assertTotals(body, 0, "0 B", 0);
    }

    @Test
    @DisplayName("on a fresh installation reports the default repositories with zero usage")
    void freshInstallation() throws Exception {
      // The startup seeding has finished (AbstractIntegrationTest waits for it): one default
      // repository per type, none of which has any usage. The listeners' own behaviour is asserted
      // against an empty database in DefaultRepoSeedingIT.
      final var seededTypes = new HashSet<RepoType>();
      for (final var repo : UsageControllerIT.this.repoRepository.findAll()) {
        seededTypes.add(repo.getType());
      }
      assertThat(seededTypes).containsExactlyInAnyOrder(RepoType.values());
      assertThat(UsageControllerIT.this.baselineRepos)
          .isGreaterThanOrEqualTo(RepoType.values().length);

      final var body =
          expectSuccess(
              UsageControllerIT.this.getUsages(UsageControllerIT.this.seededAdminBearerToken()));

      assertTotals(body, 0, "0 B", 0);
    }

    @Test
    @DisplayName("counts repositories that have no usage yet")
    void repositoriesWithoutUsage() throws Exception {
      UsageControllerIT.this.createRepo(RepoType.MAVEN);
      UsageControllerIT.this.createRepo(RepoType.NPM);

      final var body =
          expectSuccess(
              UsageControllerIT.this.getUsages(UsageControllerIT.this.seededAdminBearerToken()));

      assertTotals(body, 0, "0 B", 2);
    }

    /**
     * {@code TotalUsageInfo} only carries the instance-wide {@code diskUsed} and {@code
     * reposCount}; there are no per-type figures, so this checks that repositories of
     * <em>every</em> type, public and private, feed into both.
     */
    @Test
    @DisplayName("sums usage and counts repositories across every repository type")
    void allRepositoryTypes() throws Exception {
      var expectedTotal = 0L;
      for (final var type : RepoType.values()) {
        final var usage = (type.ordinal() + 1) * 1000L;
        final var repoId = UsageControllerIT.this.createRepo(type, type.ordinal() % 2 == 1);
        UsageControllerIT.this.recordUsage(repoId, usage);
        expectedTotal += usage;
      }
      assertThat(expectedTotal).isEqualTo(45_000L);

      final var body =
          expectSuccess(
              UsageControllerIT.this.getUsages(UsageControllerIT.this.seededAdminBearerToken()));

      assertTotals(body, 45_000, "43.95 KB", RepoType.values().length);
      assertThat(UsageControllerIT.this.repoRepository.getTotalDiskUsage())
          .isEqualTo(UsageControllerIT.this.baselineDiskUsage + 45_000L);
      assertThat(UsageControllerIT.this.repoRepository.count())
          .isEqualTo(UsageControllerIT.this.baselineRepos + RepoType.values().length);
    }

    @Test
    @DisplayName("sums several repositories of the same type")
    void sameTypeRepositories() throws Exception {
      UsageControllerIT.this.recordUsage(UsageControllerIT.this.createRepo(RepoType.DOCKER), 1024);
      UsageControllerIT.this.recordUsage(UsageControllerIT.this.createRepo(RepoType.DOCKER), 1024);
      UsageControllerIT.this.recordUsage(UsageControllerIT.this.createRepo(RepoType.DOCKER), 512);

      final var body =
          expectSuccess(
              UsageControllerIT.this.getUsages(UsageControllerIT.this.seededAdminBearerToken()));

      assertTotals(body, 2560, "2.50 KB", 3);
    }

    @ParameterizedTest(name = "{0} bytes -> {1}")
    @MethodSource("humanReadableSizes")
    @DisplayName("renders the total in the largest fitting unit, rounded up to two decimals")
    void humanReadableText(final long bytes, final String text) throws Exception {
      UsageControllerIT.this.recordUsage(UsageControllerIT.this.createRepo(RepoType.MAVEN), bytes);

      final var body =
          expectSuccess(
              UsageControllerIT.this.getUsages(UsageControllerIT.this.seededAdminBearerToken()));

      assertTotals(body, bytes, text, 1);
    }

    static Stream<Arguments> humanReadableSizes() {
      return Stream.of(
          Arguments.of(0L, "0 B"),
          Arguments.of(1L, "1 B"),
          Arguments.of(1023L, "1023 B"),
          Arguments.of(1024L, "1.00 KB"),
          Arguments.of(1025L, "1.01 KB"),
          Arguments.of(1536L, "1.50 KB"),
          // Rounding up at two decimals reaches 1024.00 KB, so the text rolls over to "1.00 MB".
          Arguments.of(1_048_575L, "1.00 MB"),
          Arguments.of(1_048_576L, "1.00 MB"),
          Arguments.of(1_073_741_824L, "1.00 GB"),
          Arguments.of(1_099_511_627_776L, "1.00 TB"),
          Arguments.of(5_497_558_138_880L, "5.00 TB"));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Usage reflects changes
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("usage reflects changes")
  class Changes {

    @Test
    @DisplayName("moves up as UsageUpdateService records uploads")
    void uploadsIncreaseTotals() throws Exception {
      final var token = UsageControllerIT.this.seededAdminBearerToken();
      final var maven = UsageControllerIT.this.createRepo(RepoType.MAVEN);
      final var npm = UsageControllerIT.this.createRepo(RepoType.NPM);
      assertTotals(expectSuccess(UsageControllerIT.this.getUsages(token)), 0, "0 B", 2);

      UsageControllerIT.this.recordUsage(maven, 2048);
      assertTotals(expectSuccess(UsageControllerIT.this.getUsages(token)), 2048, "2.00 KB", 2);

      UsageControllerIT.this.recordUsage(npm, 1024);
      assertTotals(expectSuccess(UsageControllerIT.this.getUsages(token)), 3072, "3.00 KB", 2);

      UsageControllerIT.this.recordUsage(maven, 1024);
      assertTotals(expectSuccess(UsageControllerIT.this.getUsages(token)), 4096, "4.00 KB", 2);
    }

    @Test
    @DisplayName("moves down when UsageUpdateService records a removal")
    void removalsDecreaseTotals() throws Exception {
      final var token = UsageControllerIT.this.seededAdminBearerToken();
      final var repoId = UsageControllerIT.this.createRepo(RepoType.DOCKER);
      UsageControllerIT.this.recordUsage(repoId, 5000);
      assertTotals(expectSuccess(UsageControllerIT.this.getUsages(token)), 5000, "4.89 KB", 1);

      UsageControllerIT.this.recordUsage(repoId, -2000);

      assertTotals(expectSuccess(UsageControllerIT.this.getUsages(token)), 3000, "2.93 KB", 1);
    }

    @Test
    @DisplayName("drops a deleted repository's usage and count, back to zero after the last one")
    void repositoryDeletionDecreasesTotals() throws Exception {
      final var token = UsageControllerIT.this.seededAdminBearerToken();
      final var first = UsageControllerIT.this.createRepo(RepoType.MAVEN);
      final var second = UsageControllerIT.this.createRepo(RepoType.NPM);
      UsageControllerIT.this.recordUsage(first, 1024);
      UsageControllerIT.this.recordUsage(second, 2048);
      assertTotals(expectSuccess(UsageControllerIT.this.getUsages(token)), 3072, "3.00 KB", 2);

      UsageControllerIT.this.repoTxService.deleteRepo(first);
      assertTotals(expectSuccess(UsageControllerIT.this.getUsages(token)), 2048, "2.00 KB", 1);

      UsageControllerIT.this.repoTxService.deleteRepo(second);
      assertTotals(expectSuccess(UsageControllerIT.this.getUsages(token)), 0, "0 B", 0);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Routing
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("routing")
  class Routing {

    /**
     * The port-based handler mapping does not raise {@code HttpRequestMethodNotSupportedException}
     * for a verb the path does not map, so the request falls through to the static-resource handler
     * and fails with the servlet {@code NoResourceFoundException}, which {@code ErrorHandler}
     * answers with 404 {@code itemNotFound} (RPS-849).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedMethods")
    @DisplayName("answers 404 itemNotFound for a verb the path does not map")
    void unsupportedMethod(final String name, final MockHttpServletRequestBuilder request)
        throws Exception {
      final var token = UsageControllerIT.this.seededAdminBearerToken();

      expectError(
          UsageControllerIT.this.perform(request.header(AUTHORIZATION, token)),
          HttpStatus.NOT_FOUND,
          "itemNotFound",
          null,
          "The requested item is not found.");
    }

    static Stream<Arguments> unsupportedMethods() {
      return Stream.of(
          Arguments.of("POST /api/usages", post(USAGES_PATH)),
          Arguments.of("PUT /api/usages", put(USAGES_PATH)),
          Arguments.of("PATCH /api/usages", patch(USAGES_PATH)),
          Arguments.of("DELETE /api/usages", delete(USAGES_PATH)));
    }
  }
}
