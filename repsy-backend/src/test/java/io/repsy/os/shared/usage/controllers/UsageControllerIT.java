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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.repsy.core.events.UserCreatedEvent;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.RepsyApplication;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.PasswordGeneratorUtil;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.repositories.UserRepository;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full-stack integration tests for {@code GET /api/usages}, exercising the real Spring context, MVC
 * dispatch and a containerized PostgreSQL database (Flyway-migrated) end to end.
 *
 * <p>Follows the pattern established by {@code ProfileControllerIT} and {@code UserControllerIT}:
 * requests go through {@link MockMvc}, but {@code UsageController} is only registered on the "api"
 * multiport connector, so every request is routed through {@link #apiPort()} to fake the local port
 * onto {@code multiport.ports.api} (8080).
 *
 * <p>Unlike those classes, this one is <em>not</em> {@code @Transactional}. {@code
 * UsageUpdateService#updateUsage} is {@code @Async}: it runs on another thread in its own
 * transaction, so it can only see repositories that have already been committed. Every fixture is
 * therefore committed for real.
 *
 * <p>{@code TotalUsageInfo} is a global aggregate over the {@code repo} table, and a fresh
 * application is not empty: on startup {@code AdminUserInitializer} seeds the {@code admin} user
 * and publishes a {@code UserCreatedEvent}, whose {@code @Async} per-protocol listeners create one
 * default zero-usage repository per {@link RepoType}. {@link #resetRepos()} waits for that once and
 * then empties the table before every test, so each test starts from zero repositories and asserts
 * absolute totals; {@link #freshInstallation()} re-fires the event to cover the untouched state.
 * {@link #cleanUp()} removes what a test created (the seeded {@code admin} user is left alone).
 * Tests must not run in parallel with each other.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(
    classes = RepsyApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("UsageController GET /api/usages")
class UsageControllerIT {

  private static final int API_PORT = 8080;
  private static final String USAGES_PATH = "/api/usages";
  private static final String VALID_PASSWORD = "Password1!";
  private static final String SEEDED_ADMIN_USERNAME = "admin";
  private static final String UUID_PATTERN =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
  private static final String USER_NOT_FOUND_TEXT = "User not found.";
  private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(10);

  private static final String[] ENVELOPE_KEYS = {"msgId", "type", "data", "errorCode", "text"};
  private static final String[] TOTAL_USAGE_KEYS = {"diskUsed", "reposCount"};
  private static final String[] USAGE_INFO_KEYS = {"value", "text"};

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18")
          .withDatabaseName("repsy")
          .withUsername("repsy")
          .withPassword("repsy123");

  @DynamicPropertySource
  static void registerDynamicProperties(final DynamicPropertyRegistry registry) {
    registry.add("storage-gateway.fs.base-path", UsageControllerIT::tempStoragePath);
  }

  private static String tempStoragePath() {
    try {
      return Files.createTempDirectory("repsy-usages-it").toString();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtils jwtUtils;
  @Autowired private UserTxService userTxService;
  @Autowired private UserRepository userRepository;
  @Autowired private RepoTxService repoTxService;
  @Autowired private RepoRepository repoRepository;
  @Autowired private UsageUpdateService usageUpdateService;
  @Autowired private ApplicationEventPublisher eventPublisher;

  private final List<UUID> createdUserIds = new ArrayList<>();

  /** Set once the startup seeding of the default repositories has been observed to finish. */
  private static final AtomicBoolean STARTUP_SEEDING_AWAITED = new AtomicBoolean();

  @BeforeEach
  void resetRepos() {
    if (STARTUP_SEEDING_AWAITED.compareAndSet(false, true)) {
      await()
          .atMost(ASYNC_TIMEOUT)
          .untilAsserted(
              () -> assertThat(this.repoRepository.count()).isEqualTo(RepoType.values().length));
    }
    this.repoRepository.deleteAll();
  }

  @AfterEach
  void cleanUp() {
    this.repoRepository.deleteAll();
    this.userRepository.deleteAllById(this.createdUserIds);
  }

  // ---------------------------------------------------------------------------------------------
  // Request / fixture helpers
  // ---------------------------------------------------------------------------------------------

  private static RequestPostProcessor apiPort() {
    return request -> {
      request.setLocalPort(API_PORT);
      return request;
    };
  }

  private static String randomTag() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }

  private ResultActions perform(final MockHttpServletRequestBuilder request) throws Exception {
    return this.mockMvc.perform(request.with(apiPort()));
  }

  private ResultActions getUsages(final String authorization) throws Exception {
    return this.perform(get(USAGES_PATH).header(AUTHORIZATION, authorization));
  }

  /** Creates a committed user with the given role and returns its username. */
  private String createUser(final UserRole role) {
    final var username = "usage" + randomTag();
    final var salt = PasswordGeneratorUtil.generateSalt();
    final var hash = PasswordGeneratorUtil.hashPassword(VALID_PASSWORD, salt);
    final var userInfo = this.userTxService.create(username, role, hash, salt);
    this.createdUserIds.add(userInfo.getId());
    return username;
  }

  private String bearerTokenFor(final String username) {
    final var userId = this.userRepository.findByUsername(username).orElseThrow().getId();
    return this.bearerTokenFor(userId, username);
  }

  private String bearerTokenFor(final UUID userId, final String username) {
    return AuthUtils.AUTH_BEARER
        + this.jwtUtils.createTokenWithDuration(userId, username, Duration.ofMinutes(30));
  }

  private String expiredBearerTokenFor(final String username) {
    final var userId = this.userRepository.findByUsername(username).orElseThrow().getId();
    return AuthUtils.AUTH_BEARER
        + this.jwtUtils.createTokenWithDuration(userId, username, Duration.ofSeconds(-30));
  }

  /** A valid bearer token for the {@code admin} user that the application seeds at startup. */
  private String adminBearerToken() {
    return this.bearerTokenFor(SEEDED_ADMIN_USERNAME);
  }

  private UUID createRepo(final RepoType type, final boolean privateRepo) {
    final var name = "u" + randomTag() + "-" + type.name().toLowerCase(Locale.ROOT);
    return this.repoTxService.createRepo(name, type, privateRepo, null).getId();
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
    final var body =
        result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

    final Map<String, Object> envelope = JsonPath.read(body, "$");
    assertThat(envelope)
        .containsOnlyKeys(ENVELOPE_KEYS)
        .containsEntry("msgId", "usageFetched")
        .containsEntry("type", "SUCCESS")
        .containsEntry("errorCode", null)
        .containsEntry("text", "Usage fetched");
    return body;
  }

  /** Asserts the complete {@code TotalUsageInfo} shape: exact key sets and every value. */
  private static void assertTotals(
      final String body, final long diskUsed, final String diskUsedText, final long reposCount) {
    final Map<String, Object> data = JsonPath.read(body, "$.data");
    assertThat(data).containsOnlyKeys(TOTAL_USAGE_KEYS);
    assertThat(number(data.get("reposCount"))).isEqualTo(reposCount);

    final Map<String, Object> usageInfo = JsonPath.read(body, "$.data.diskUsed");
    assertThat(usageInfo).containsOnlyKeys(USAGE_INFO_KEYS).containsEntry("text", diskUsedText);
    assertThat(number(usageInfo.get("value"))).isEqualTo(diskUsed);
  }

  /** Asserts a complete ERROR envelope, including the generated {@code errorCode} UUID. */
  private static void expectError(
      final ResultActions result,
      final HttpStatus expectedStatus,
      final String msgId,
      final String data,
      final String text)
      throws Exception {
    final var body =
        result
            .andExpect(status().is(expectedStatus.value()))
            .andReturn()
            .getResponse()
            .getContentAsString();

    final Map<String, Object> envelope = JsonPath.read(body, "$");
    assertThat(envelope)
        .containsOnlyKeys(ENVELOPE_KEYS)
        .containsEntry("msgId", msgId)
        .containsEntry("type", "ERROR")
        .containsEntry("data", data)
        .containsEntry("text", text);
    assertThat((String) envelope.get("errorCode")).matches(UUID_PATTERN);
  }

  private static void expectAccessNotAllowed(final ResultActions result) throws Exception {
    expectError(
        result,
        HttpStatus.FORBIDDEN,
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
    @DisplayName("returns 403 when the Authorization header is missing")
    void missingAuthorizationHeader() throws Exception {
      expectError(
          UsageControllerIT.this.perform(get(USAGES_PATH)),
          HttpStatus.FORBIDDEN,
          "Missing Request Header",
          null,
          "Missing Request Header");
    }

    @Test
    @DisplayName("returns 403 for a header without a Bearer prefix")
    void nonBearerAuthorizationHeader() throws Exception {
      expectAccessNotAllowed(UsageControllerIT.this.getUsages("Basic dXNlcjpw"));
    }

    @Test
    @DisplayName("returns 403 for a malformed/garbage bearer token")
    void malformedBearerToken() throws Exception {
      expectAccessNotAllowed(UsageControllerIT.this.getUsages("Bearer not-a-jwt"));
    }

    @Test
    @DisplayName("returns 403 for an expired token")
    void expiredToken() throws Exception {
      final var token = UsageControllerIT.this.expiredBearerTokenFor(SEEDED_ADMIN_USERNAME);

      expectError(
          UsageControllerIT.this.getUsages(token),
          HttpStatus.FORBIDDEN,
          "sessionExpired",
          "sessionExpired",
          "Session expired.");
    }

    @Test
    @DisplayName("returns 404 when the token's user never existed")
    void tokenUserNeverExisted() throws Exception {
      // Authentication resolves the caller by the token's username claim, not by its subject id.
      final var token =
          UsageControllerIT.this.bearerTokenFor(UUID.randomUUID(), "ghost" + randomTag());

      expectError(
          UsageControllerIT.this.getUsages(token),
          HttpStatus.NOT_FOUND,
          "userNotFound",
          "userNotFound",
          USER_NOT_FOUND_TEXT);
    }

    @Test
    @DisplayName("returns 404 once the token's user has been deleted, though the token is valid")
    void tokenUserDeleted() throws Exception {
      final var username = UsageControllerIT.this.createUser(UserRole.ADMIN);
      final var token = UsageControllerIT.this.bearerTokenFor(username);
      expectSuccess(UsageControllerIT.this.getUsages(token));

      UsageControllerIT.this.userRepository.delete(
          UsageControllerIT.this.userRepository.findByUsername(username).orElseThrow());

      expectError(
          UsageControllerIT.this.getUsages(token),
          HttpStatus.NOT_FOUND,
          "userNotFound",
          "userNotFound",
          USER_NOT_FOUND_TEXT);
    }

    /**
     * The controller only calls {@code authenticate}, never {@code requireAdmin}, so any signed-in
     * user sees the instance-wide totals. Pinned as current behavior; if the endpoint is ever
     * restricted to admins, this becomes a 401 {@code accessDenied} like {@code /api/users}.
     */
    @Test
    @DisplayName("currently lets a non-admin user read the totals (only authenticate is required)")
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
    @DisplayName("without any repository returns zero usage and a zero count")
    void noRepositories() throws Exception {
      assertThat(UsageControllerIT.this.repoRepository.count()).isZero();

      final var body =
          expectSuccess(
              UsageControllerIT.this.getUsages(UsageControllerIT.this.adminBearerToken()));

      assertTotals(body, 0, "0 B", 0);
    }

    @Test
    @DisplayName("on a fresh installation reports the default repositories with zero usage")
    void freshInstallation() throws Exception {
      // Same event, same async listeners as at startup: one default repository per type.
      UsageControllerIT.this.eventPublisher.publishEvent(
          new UserCreatedEvent<>(UUID.randomUUID(), SEEDED_ADMIN_USERNAME));
      await()
          .atMost(ASYNC_TIMEOUT)
          .untilAsserted(
              () ->
                  assertThat(UsageControllerIT.this.repoRepository.count())
                      .isEqualTo(RepoType.values().length));

      final var body =
          expectSuccess(
              UsageControllerIT.this.getUsages(UsageControllerIT.this.adminBearerToken()));

      assertTotals(body, 0, "0 B", RepoType.values().length);
    }

    @Test
    @DisplayName("counts repositories that have no usage yet")
    void repositoriesWithoutUsage() throws Exception {
      UsageControllerIT.this.createRepo(RepoType.MAVEN);
      UsageControllerIT.this.createRepo(RepoType.NPM);

      final var body =
          expectSuccess(
              UsageControllerIT.this.getUsages(UsageControllerIT.this.adminBearerToken()));

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
              UsageControllerIT.this.getUsages(UsageControllerIT.this.adminBearerToken()));

      assertTotals(body, 45_000, "43.95 KB", RepoType.values().length);
      assertThat(UsageControllerIT.this.repoRepository.getTotalDiskUsage()).isEqualTo(45_000L);
      assertThat(UsageControllerIT.this.repoRepository.count()).isEqualTo(RepoType.values().length);
    }

    @Test
    @DisplayName("sums several repositories of the same type")
    void sameTypeRepositories() throws Exception {
      UsageControllerIT.this.recordUsage(UsageControllerIT.this.createRepo(RepoType.DOCKER), 1024);
      UsageControllerIT.this.recordUsage(UsageControllerIT.this.createRepo(RepoType.DOCKER), 1024);
      UsageControllerIT.this.recordUsage(UsageControllerIT.this.createRepo(RepoType.DOCKER), 512);

      final var body =
          expectSuccess(
              UsageControllerIT.this.getUsages(UsageControllerIT.this.adminBearerToken()));

      assertTotals(body, 2560, "2.50 KB", 3);
    }

    @ParameterizedTest(name = "{0} bytes -> {1}")
    @MethodSource("humanReadableSizes")
    @DisplayName("renders the total in the largest fitting unit, rounded up to two decimals")
    void humanReadableText(final long bytes, final String text) throws Exception {
      UsageControllerIT.this.recordUsage(UsageControllerIT.this.createRepo(RepoType.MAVEN), bytes);

      final var body =
          expectSuccess(
              UsageControllerIT.this.getUsages(UsageControllerIT.this.adminBearerToken()));

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
      final var token = UsageControllerIT.this.adminBearerToken();
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
      final var token = UsageControllerIT.this.adminBearerToken();
      final var repoId = UsageControllerIT.this.createRepo(RepoType.DOCKER);
      UsageControllerIT.this.recordUsage(repoId, 5000);
      assertTotals(expectSuccess(UsageControllerIT.this.getUsages(token)), 5000, "4.89 KB", 1);

      UsageControllerIT.this.recordUsage(repoId, -2000);

      assertTotals(expectSuccess(UsageControllerIT.this.getUsages(token)), 3000, "2.93 KB", 1);
    }

    @Test
    @DisplayName("drops a deleted repository's usage and count, back to zero after the last one")
    void repositoryDeletionDecreasesTotals() throws Exception {
      final var token = UsageControllerIT.this.adminBearerToken();
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
      final var token = UsageControllerIT.this.adminBearerToken();

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
