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
package io.repsy.os.server.security.scan.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.doReturn;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.RepsyApplication;
import io.repsy.os.server.security.scan.dtos.FixStatus;
import io.repsy.os.server.security.scan.dtos.Severity;
import io.repsy.os.server.security.scan.repositories.VulnerabilityScanRepository;
import io.repsy.os.server.security.scan.services.VulnerabilityScanTxService;
import io.repsy.os.server.security.scanner.VulnerabilityScanner;
import io.repsy.os.server.security.scanner.VulnerabilityScannerRegistry;
import io.repsy.os.server.security.scanner.dtos.ScanOutcome;
import io.repsy.os.server.security.scanner.dtos.ScanRequest;
import io.repsy.os.server.security.scanner.dtos.ScannerFinding;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.PasswordGeneratorUtil;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.repositories.UserRepository;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full-stack integration tests for the global security-scan summary API ({@code GET
 * /api/security/scans}, {@code GET /api/security/scans/summary} and {@code GET
 * /api/security/supported-repo-types}), exercising the real Spring context, MVC dispatch and a
 * containerized PostgreSQL database (Flyway-migrated) end to end.
 *
 * <p>Follows the pattern established by {@code ProfileControllerIT} and {@code UserControllerIT}:
 * requests go through {@link MockMvc}, but {@code SecurityScanController} is only registered on the
 * "api" multiport connector, so every request is routed through {@link #apiPort()} to fake the
 * local port onto {@code multiport.ports.api} (8080).
 *
 * <p>Like {@code UsageControllerIT} (and unlike the profile/user classes) this one is <em>not</em>
 * {@code @Transactional}: {@code created_at} is a {@code @CreationTimestamp} that is overwritten on
 * INSERT, so tests pin it with a bulk update afterwards, and inside one persistence context the
 * controller would then be handed the stale managed entity instead of the pinned row. Every fixture
 * is therefore committed for real and removed again in {@link #cleanUp()}.
 *
 * <p>Scans are seeded through the real {@link VulnerabilityScanTxService} lifecycle ({@code
 * createPendingScan} → {@code markQueued} → {@code markRunning} → {@code recordScanOutcome} /
 * {@code recordScanFailure}), so no Trivy service is needed and the stored rows are exactly what
 * production writes. The {@code admin} user seeded by {@code AdminUserInitializer} is used as the
 * authenticated administrator and left alone.
 *
 * <p><b>Scanner registry:</b> the context wires the <em>real</em> {@link
 * VulnerabilityScannerRegistry}. {@code repsy.security.scanner} is unset, so the registry holds the
 * real {@code NoOpVulnerabilityScanner} (wildcard {@code *}, which the registry never reports),
 * plus {@link StubScannerConfig}, a scanner declaring the same repo types as the real Trivy scanner
 * (MAVEN, NPM, PYPI, DOCKER) without needing its HTTP client or configuration. The "scanner
 * disabled" answer (an empty list) is covered by spying the registry for that one test.
 */
@Testcontainers
@AutoConfigureMockMvc
@Import(SecurityScanControllerIT.StubScannerConfig.class)
@SpringBootTest(
    classes = RepsyApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("SecurityScanController /api/security/*")
class SecurityScanControllerIT {

  private static final int API_PORT = 8080;
  private static final String SCANS_PATH = "/api/security/scans";
  private static final String SUMMARY_PATH = "/api/security/scans/summary";
  private static final String SUPPORTED_REPO_TYPES_PATH = "/api/security/supported-repo-types";
  private static final String VALID_PASSWORD = "Password1!";
  private static final String SEEDED_ADMIN_USERNAME = "admin";
  private static final String UUID_PATTERN =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
  private static final String USER_NOT_FOUND_TEXT = "User not found.";
  private static final String SCANNER_NAME = "trivy";
  private static final String SCANNER_VERSION = "0.58.0";
  private static final String FAILURE_MESSAGE = "Scanner timed out";
  private static final Instant BASE_TIME = Instant.parse("2026-03-01T10:00:00Z");
  private static final Set<String> STUB_SCANNER_REPO_TYPES =
      Set.of("MAVEN", "NPM", "PYPI", "DOCKER");

  private static final String[] ENVELOPE_KEYS = {"msgId", "type", "data", "errorCode", "text"};
  private static final List<String> SCAN_INFO_KEYS =
      List.of(
          "id",
          "repoName",
          "repoType",
          "artifactName",
          "artifactVersion",
          "status",
          "highestSeverity",
          "scannerName",
          "scannerVersion",
          "errorMessage",
          "createdAt",
          "startedAt",
          "completedAt");
  private static final String[] PAGE_KEYS = {"size", "number", "totalElements", "totalPages"};
  private static final String[] SUMMARY_KEYS = {
    "criticalCount", "highCount", "mediumCount", "lowCount", "unknownCount", "totalCount"
  };

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18")
          .withDatabaseName("repsy")
          .withUsername("repsy")
          .withPassword("repsy123");

  @DynamicPropertySource
  static void registerDynamicProperties(final DynamicPropertyRegistry registry) {
    registry.add("storage-gateway.fs.base-path", SecurityScanControllerIT::tempStoragePath);
    // Lets the query-count tests read the number of statements a request ran.
    registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
  }

  private static String tempStoragePath() {
    try {
      return Files.createTempDirectory("repsy-security-scan-it").toString();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Adds a second scanner next to the {@code NoOpVulnerabilityScanner} so the real registry has
   * concrete repo types to report. It is never asked to scan anything.
   */
  @TestConfiguration(proxyBeanMethods = false)
  static class StubScannerConfig {

    @Bean
    VulnerabilityScanner stubVulnerabilityScanner() {
      return new VulnerabilityScanner() {
        @Override
        public void scan(final @NonNull ScanRequest request) {
          throw new UnsupportedOperationException("The stub scanner never scans");
        }

        @Override
        public @NonNull String getName() {
          return "it-stub";
        }

        @Override
        public @NonNull Set<String> getSupportedRepoTypes() {
          return STUB_SCANNER_REPO_TYPES;
        }
      };
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtils jwtUtils;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private UserTxService userTxService;
  @Autowired private UserRepository userRepository;
  @Autowired private RepoTxService repoTxService;
  @Autowired private RepoRepository repoRepository;
  @Autowired private VulnerabilityScanRepository scanRepository;
  @Autowired private VulnerabilityScanTxService scanTxService;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @MockitoSpyBean private VulnerabilityScannerRegistry scannerRegistry;

  private final List<UUID> createdUserIds = new ArrayList<>();
  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final AtomicInteger cveCounter = new AtomicInteger();

  @BeforeEach
  void resetScans() {
    // Scans (and their findings, by FK cascade) are global; start every test from none.
    this.scanRepository.deleteAll();
  }

  @AfterEach
  void cleanUp() {
    this.scanRepository.deleteAll();
    this.repoRepository.deleteAllById(this.createdRepoIds);
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

  private ResultActions getScans(final String authorization) throws Exception {
    return this.perform(get(SCANS_PATH).header(AUTHORIZATION, authorization));
  }

  private ResultActions getScans(final String authorization, final Map<String, String> params)
      throws Exception {
    final var request = get(SCANS_PATH).header(AUTHORIZATION, authorization);
    params.forEach(request::param);
    return this.perform(request);
  }

  private ResultActions getSummary(final String authorization) throws Exception {
    return this.perform(get(SUMMARY_PATH).header(AUTHORIZATION, authorization));
  }

  private ResultActions getSummary(final String authorization, final Map<String, String> params)
      throws Exception {
    final var request = get(SUMMARY_PATH).header(AUTHORIZATION, authorization);
    params.forEach(request::param);
    return this.perform(request);
  }

  /** Creates a committed user with the given role and returns its username. */
  private String createUser(final UserRole role) {
    final var username = "scan" + randomTag();
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

  /** A repository as far as these tests care: its id plus the name/type the API reports. */
  private record TestRepo(UUID id, String name, RepoType type) {}

  private TestRepo createRepo(final RepoType type) {
    final var name = "s" + randomTag() + "-" + type.name().toLowerCase(Locale.ROOT);
    final var id = this.repoTxService.createRepo(name, type, false, null).getId();
    this.createdRepoIds.add(id);
    return new TestRepo(id, name, type);
  }

  private ScannerFinding finding(final Severity severity) {
    final var n = this.cveCounter.incrementAndGet();
    return new ScannerFinding(
        "CVE-2026-" + n,
        severity,
        "libfoo",
        "1.0." + n,
        "1.0." + (n + 1),
        "Description of CVE-2026-" + n,
        "https://example.com/CVE-2026-" + n,
        FixStatus.FIXED,
        7.5,
        "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N");
  }

  private List<ScannerFinding> findings(final Severity... severities) {
    return Stream.of(severities).map(this::finding).toList();
  }

  /**
   * Pins {@code created_at}. {@code @CreationTimestamp} overwrites the value on INSERT, so the row
   * is updated afterwards; that keeps ordering assertions independent of wall-clock resolution.
   */
  private void pinCreatedAt(final UUID scanId, final Instant createdAt) {
    this.jdbcTemplate.update(
        "update vulnerability_scan set created_at = ? where id = ?",
        Timestamp.from(createdAt),
        scanId);
  }

  /** A finished scan, driven through the real lifecycle, with the given findings recorded. */
  private UUID completedScan(
      final TestRepo repo,
      final String artifact,
      final String version,
      final Instant createdAt,
      final List<ScannerFinding> findings) {

    final var scanId =
        this.scanTxService.createPendingScan(repo.id(), artifact, version, SCANNER_NAME);
    this.scanTxService.markQueued(scanId);
    this.scanTxService.markRunning(scanId);
    this.scanTxService.recordScanOutcome(scanId, new ScanOutcome(findings, SCANNER_VERSION));
    this.pinCreatedAt(scanId, createdAt);
    return scanId;
  }

  private UUID completedScan(
      final TestRepo repo, final String artifact, final String version, final Instant createdAt) {
    return this.completedScan(repo, artifact, version, createdAt, List.of());
  }

  private UUID failedScan(
      final TestRepo repo, final String artifact, final String version, final Instant createdAt) {

    final var scanId =
        this.scanTxService.createPendingScan(repo.id(), artifact, version, SCANNER_NAME);
    this.scanTxService.markQueued(scanId);
    this.scanTxService.markRunning(scanId);
    this.scanTxService.recordScanFailure(scanId, FAILURE_MESSAGE);
    this.pinCreatedAt(scanId, createdAt);
    return scanId;
  }

  private UUID pendingScan(
      final TestRepo repo, final String artifact, final String version, final Instant createdAt) {

    final var scanId =
        this.scanTxService.createPendingScan(repo.id(), artifact, version, SCANNER_NAME);
    this.pinCreatedAt(scanId, createdAt);
    return scanId;
  }

  private UUID runningScan(
      final TestRepo repo, final String artifact, final String version, final Instant createdAt) {

    final var scanId = this.pendingScan(repo, artifact, version, createdAt);
    this.scanTxService.markQueued(scanId);
    this.scanTxService.markRunning(scanId);
    return scanId;
  }

  private UUID queuedScan(
      final TestRepo repo, final String artifact, final String version, final Instant createdAt) {

    final var scanId =
        this.scanTxService.createPendingScan(repo.id(), artifact, version, SCANNER_NAME);
    this.scanTxService.markQueued(scanId);
    this.pinCreatedAt(scanId, createdAt);
    return scanId;
  }

  private Statistics hibernateStatistics() {
    return this.entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
  }

  private static Instant at(final int minutesAfterBase) {
    return BASE_TIME.plus(minutesAfterBase, ChronoUnit.MINUTES);
  }

  // ---------------------------------------------------------------------------------------------
  // Response helpers
  // ---------------------------------------------------------------------------------------------

  /**
   * Asserts a 200 SUCCESS envelope (exact key set, {@code errorCode} null, the given {@code msgId}
   * and resolved {@code text}) and returns the raw body for further assertions on {@code data}.
   */
  private static String expectSuccess(
      final ResultActions result, final String msgId, final String text) throws Exception {
    final var body =
        result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

    final Map<String, Object> envelope = JsonPath.read(body, "$");
    assertThat(envelope)
        .containsOnlyKeys(ENVELOPE_KEYS)
        .containsEntry("msgId", msgId)
        .containsEntry("type", "SUCCESS")
        .containsEntry("errorCode", null)
        .containsEntry("text", text);
    return body;
  }

  private static String expectScans(final ResultActions result) throws Exception {
    return expectSuccess(result, "scansFetched", "Vulnerability scans fetched.");
  }

  private static String expectSummary(final ResultActions result) throws Exception {
    return expectSuccess(result, "scansSummaryFetched", "Vulnerability scans summary fetched.");
  }

  private static String expectSupportedRepoTypes(final ResultActions result) throws Exception {
    return expectSuccess(
        result, "supportedRepoTypesFetched", "Supported repository types fetched.");
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

  private static void expectValidationError(final ResultActions result, final String param)
      throws Exception {
    expectError(
        result,
        HttpStatus.BAD_REQUEST,
        "validationError",
        param,
        "Incoming data couldn't be validated.");
  }

  /**
   * Asserts the exact key set of a {@code VulnerabilityScanInfo}. Null properties are omitted from
   * the JSON rather than serialized as {@code null}, so a scan is expected to carry every key
   * except the {@code absent} ones (which are asserted to be missing, not merely null).
   */
  private static void assertScanKeys(final Map<String, Object> scan, final String... absent) {
    final var expected = new ArrayList<>(SCAN_INFO_KEYS);
    expected.removeAll(List.of(absent));
    assertThat(scan).containsOnlyKeys(expected);
  }

  /** Asserts the {@code PagedModel} metadata block in full. */
  private static void assertPage(
      final String body,
      final int size,
      final int number,
      final long totalElements,
      final int pages) {
    final Map<String, Object> page = JsonPath.read(body, "$.data.page");
    assertThat(page).containsOnlyKeys(PAGE_KEYS);
    assertThat(number(page.get("size"))).isEqualTo(size);
    assertThat(number(page.get("number"))).isEqualTo(number);
    assertThat(number(page.get("totalElements"))).isEqualTo(totalElements);
    assertThat(number(page.get("totalPages"))).isEqualTo(pages);
  }

  /** Asserts the {@code PagedModel} wrapper has exactly {@code content} and {@code page}. */
  private static void assertPagedModelShape(final String body) {
    final Map<String, Object> data = JsonPath.read(body, "$.data");
    assertThat(data).containsOnlyKeys("content", "page");
  }

  private static List<Map<String, Object>> content(final String body) {
    return JsonPath.read(body, "$.data.content");
  }

  private static List<String> artifactVersions(final String body) {
    return content(body).stream().map(scan -> (String) scan.get("artifactVersion")).toList();
  }

  private static void assertSummary(
      final String body,
      final int critical,
      final int high,
      final int medium,
      final int low,
      final int unknown) {
    final Map<String, Object> data = JsonPath.read(body, "$.data");
    assertThat(data).containsOnlyKeys(SUMMARY_KEYS);
    assertThat(number(data.get("criticalCount"))).isEqualTo(critical);
    assertThat(number(data.get("highCount"))).isEqualTo(high);
    assertThat(number(data.get("mediumCount"))).isEqualTo(medium);
    assertThat(number(data.get("lowCount"))).isEqualTo(low);
    assertThat(number(data.get("unknownCount"))).isEqualTo(unknown);
    assertThat(number(data.get("totalCount")))
        .isEqualTo((long) critical + high + medium + low + unknown);
  }

  private static long number(final Object json) {
    return ((Number) json).longValue();
  }

  private static Instant instantOrNull(final Object json) {
    return json == null ? null : Instant.parse((String) json);
  }

  // ---------------------------------------------------------------------------------------------
  // Authentication & authorization (the two admin endpoints)
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("authentication & authorization (/scans and /scans/summary)")
  class Security {

    static Stream<Arguments> adminEndpoints() {
      return Stream.of(Arguments.of(SCANS_PATH), Arguments.of(SUMMARY_PATH));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adminEndpoints")
    @DisplayName("returns 403 when the Authorization header is missing")
    void missingAuthorizationHeader(final String path) throws Exception {
      expectError(
          SecurityScanControllerIT.this.perform(get(path)),
          HttpStatus.FORBIDDEN,
          "missingRequestHeader",
          "Authorization",
          "A required request header is missing.");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adminEndpoints")
    @DisplayName("returns 403 for a header without a Bearer prefix")
    void nonBearerAuthorizationHeader(final String path) throws Exception {
      expectAccessNotAllowed(
          SecurityScanControllerIT.this.perform(get(path).header(AUTHORIZATION, "Basic dXNlcjpw")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adminEndpoints")
    @DisplayName("returns 403 for a malformed/garbage bearer token")
    void malformedBearerToken(final String path) throws Exception {
      expectAccessNotAllowed(
          SecurityScanControllerIT.this.perform(
              get(path).header(AUTHORIZATION, "Bearer not-a-jwt")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adminEndpoints")
    @DisplayName("returns 403 sessionExpired for an expired token")
    void expiredToken(final String path) throws Exception {
      final var token = SecurityScanControllerIT.this.expiredBearerTokenFor(SEEDED_ADMIN_USERNAME);

      expectError(
          SecurityScanControllerIT.this.perform(get(path).header(AUTHORIZATION, token)),
          HttpStatus.FORBIDDEN,
          "sessionExpired",
          "sessionExpired",
          "Session expired.");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adminEndpoints")
    @DisplayName("returns 401 accessDenied for an authenticated non-admin caller")
    void nonAdminCaller(final String path) throws Exception {
      final var username = SecurityScanControllerIT.this.createUser(UserRole.USER);
      final var token = SecurityScanControllerIT.this.bearerTokenFor(username);

      expectError(
          SecurityScanControllerIT.this.perform(get(path).header(AUTHORIZATION, token)),
          HttpStatus.UNAUTHORIZED,
          "accessDenied",
          "accessDenied",
          "Access Denied. Please check your credentials.");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adminEndpoints")
    @DisplayName("returns 404 userNotFound when the token's user never existed")
    void tokenUserNeverExisted(final String path) throws Exception {
      // Authentication resolves the caller by the token's username claim, not by its subject id.
      final var token =
          SecurityScanControllerIT.this.bearerTokenFor(UUID.randomUUID(), "ghost" + randomTag());

      expectError(
          SecurityScanControllerIT.this.perform(get(path).header(AUTHORIZATION, token)),
          HttpStatus.NOT_FOUND,
          "userNotFound",
          "userNotFound",
          USER_NOT_FOUND_TEXT);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adminEndpoints")
    @DisplayName("returns 404 userNotFound once the token's admin has been deleted")
    void tokenUserDeleted(final String path) throws Exception {
      final var username = SecurityScanControllerIT.this.createUser(UserRole.ADMIN);
      final var token = SecurityScanControllerIT.this.bearerTokenFor(username);
      SecurityScanControllerIT.this.getScans(token).andExpect(status().isOk());

      SecurityScanControllerIT.this.userRepository.delete(
          SecurityScanControllerIT.this.userRepository.findByUsername(username).orElseThrow());

      expectError(
          SecurityScanControllerIT.this.perform(get(path).header(AUTHORIZATION, token)),
          HttpStatus.NOT_FOUND,
          "userNotFound",
          "userNotFound",
          USER_NOT_FOUND_TEXT);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adminEndpoints")
    @DisplayName("accepts any admin, not just the seeded one")
    void anotherAdmin(final String path) throws Exception {
      final var username = SecurityScanControllerIT.this.createUser(UserRole.ADMIN);
      final var token = SecurityScanControllerIT.this.bearerTokenFor(username);

      SecurityScanControllerIT.this
          .perform(get(path).header(AUTHORIZATION, token))
          .andExpect(status().isOk());
    }
  }

  // ---------------------------------------------------------------------------------------------
  // GET /api/security/scans
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("GET /api/security/scans")
  class ListScans {

    @Test
    @DisplayName("returns an empty page when there are no scans")
    void noScans() throws Exception {
      final var body =
          expectScans(
              SecurityScanControllerIT.this.getScans(
                  SecurityScanControllerIT.this.adminBearerToken()));

      assertPagedModelShape(body);
      assertThat(content(body)).isEmpty();
      assertPage(body, 10, 0, 0, 0);
    }

    @Test
    @DisplayName("ignores repositories that have no scans (a fresh instance seeds default repos)")
    void repositoriesWithoutScans() throws Exception {
      SecurityScanControllerIT.this.createRepo(RepoType.MAVEN);

      final var body =
          expectScans(
              SecurityScanControllerIT.this.getScans(
                  SecurityScanControllerIT.this.adminBearerToken()));

      assertThat(content(body)).isEmpty();
      assertPage(body, 10, 0, 0, 0);
    }

    @Test
    @DisplayName("returns the full VulnerabilityScanInfo shape for a completed scan")
    void completedScanShape() throws Exception {
      final var repo = SecurityScanControllerIT.this.createRepo(RepoType.MAVEN);
      final var scanId =
          SecurityScanControllerIT.this.completedScan(
              repo,
              "com.acme:widget",
              "1.2.3",
              at(0),
              SecurityScanControllerIT.this.findings(Severity.MEDIUM, Severity.HIGH, Severity.LOW));

      final var body =
          expectScans(
              SecurityScanControllerIT.this.getScans(
                  SecurityScanControllerIT.this.adminBearerToken()));

      assertPagedModelShape(body);
      assertPage(body, 10, 0, 1, 1);
      assertThat(content(body)).hasSize(1);

      final var scan = content(body).getFirst();
      final var persisted =
          SecurityScanControllerIT.this.scanRepository.findById(scanId).orElseThrow();
      assertScanKeys(scan, "errorMessage");
      assertThat(scan)
          .containsEntry("id", scanId.toString())
          .containsEntry("repoName", repo.name())
          .containsEntry("repoType", "MAVEN")
          .containsEntry("artifactName", "com.acme:widget")
          .containsEntry("artifactVersion", "1.2.3")
          .containsEntry("status", "COMPLETED")
          .containsEntry("highestSeverity", "HIGH")
          .containsEntry("scannerName", SCANNER_NAME)
          .containsEntry("scannerVersion", SCANNER_VERSION);
      assertThat(instantOrNull(scan.get("createdAt"))).isEqualTo(at(0));
      assertThat(instantOrNull(scan.get("startedAt")))
          .isNotNull()
          .isEqualTo(persisted.getStartedAt());
      assertThat(instantOrNull(scan.get("completedAt")))
          .isNotNull()
          .isEqualTo(persisted.getCompletedAt());
    }

    @Test
    @DisplayName("returns the failure details for a failed scan")
    void failedScanShape() throws Exception {
      final var repo = SecurityScanControllerIT.this.createRepo(RepoType.NPM);
      final var scanId = SecurityScanControllerIT.this.failedScan(repo, "left-pad", "0.0.1", at(0));

      final var body =
          expectScans(
              SecurityScanControllerIT.this.getScans(
                  SecurityScanControllerIT.this.adminBearerToken()));

      final var scan = content(body).getFirst();
      final var persisted =
          SecurityScanControllerIT.this.scanRepository.findById(scanId).orElseThrow();
      assertScanKeys(scan, "highestSeverity", "scannerVersion");
      assertThat(scan)
          .containsEntry("id", scanId.toString())
          .containsEntry("repoName", repo.name())
          .containsEntry("repoType", "NPM")
          .containsEntry("artifactName", "left-pad")
          .containsEntry("artifactVersion", "0.0.1")
          .containsEntry("status", "FAILED")
          .containsEntry("scannerName", SCANNER_NAME)
          .containsEntry("errorMessage", FAILURE_MESSAGE);
      assertThat(instantOrNull(scan.get("createdAt"))).isEqualTo(at(0));
      assertThat(instantOrNull(scan.get("startedAt"))).isEqualTo(persisted.getStartedAt());
      assertThat(instantOrNull(scan.get("completedAt")))
          .isNotNull()
          .isEqualTo(persisted.getCompletedAt());
    }

    @Test
    @DisplayName("returns a pending scan with no start or completion time")
    void pendingScanShape() throws Exception {
      final var repo = SecurityScanControllerIT.this.createRepo(RepoType.PYPI);
      final var scanId = SecurityScanControllerIT.this.pendingScan(repo, "requests", "2.0", at(0));

      final var body =
          expectScans(
              SecurityScanControllerIT.this.getScans(
                  SecurityScanControllerIT.this.adminBearerToken()));

      final var scan = content(body).getFirst();
      assertScanKeys(
          scan, "highestSeverity", "scannerVersion", "errorMessage", "startedAt", "completedAt");
      assertThat(scan)
          .containsEntry("id", scanId.toString())
          .containsEntry("repoType", "PYPI")
          .containsEntry("status", "PENDING");
      assertThat(instantOrNull(scan.get("createdAt"))).isEqualTo(at(0));
    }

    @Test
    @DisplayName("returns a running scan with a start time but no completion time")
    void runningScanShape() throws Exception {
      final var repo = SecurityScanControllerIT.this.createRepo(RepoType.DOCKER);
      final var scanId =
          SecurityScanControllerIT.this.runningScan(repo, "library/nginx", "1.27", at(0));

      final var body =
          expectScans(
              SecurityScanControllerIT.this.getScans(
                  SecurityScanControllerIT.this.adminBearerToken()));

      final var scan = content(body).getFirst();
      assertScanKeys(scan, "highestSeverity", "scannerVersion", "errorMessage", "completedAt");
      assertThat(scan).containsEntry("id", scanId.toString()).containsEntry("status", "RUNNING");
      assertThat(instantOrNull(scan.get("startedAt"))).isNotNull();
    }

    @Test
    @DisplayName("lists scans across repositories, repo types and severities, newest first")
    void orderedByCreatedAtDescending() throws Exception {
      final var maven = SecurityScanControllerIT.this.createRepo(RepoType.MAVEN);
      final var npm = SecurityScanControllerIT.this.createRepo(RepoType.NPM);
      final var docker = SecurityScanControllerIT.this.createRepo(RepoType.DOCKER);
      // Deliberately seeded out of chronological order.
      SecurityScanControllerIT.this.completedScan(
          npm, "b", "v2", at(20), SecurityScanControllerIT.this.findings(Severity.LOW));
      SecurityScanControllerIT.this.failedScan(docker, "c", "v4", at(40));
      SecurityScanControllerIT.this.completedScan(
          maven, "a", "v1", at(10), SecurityScanControllerIT.this.findings(Severity.CRITICAL));
      SecurityScanControllerIT.this.pendingScan(maven, "a", "v3", at(30));

      final var body =
          expectScans(
              SecurityScanControllerIT.this.getScans(
                  SecurityScanControllerIT.this.adminBearerToken()));

      assertThat(artifactVersions(body)).containsExactly("v4", "v3", "v2", "v1");
      assertThat(content(body))
          .extracting(scan -> scan.get("repoType"), scan -> scan.get("status"))
          .containsExactly(
              tuple("DOCKER", "FAILED"),
              tuple("MAVEN", "PENDING"),
              tuple("NPM", "COMPLETED"),
              tuple("MAVEN", "COMPLETED"));
      assertThat(content(body))
          .extracting(scan -> instantOrNull(scan.get("createdAt")))
          .containsExactly(at(40), at(30), at(20), at(10));
      assertPage(body, 10, 0, 4, 1);
    }

    @Test
    @DisplayName("reports the highest severity among a scan's findings, CRITICAL first")
    void highestSeverityPerScan() throws Exception {
      final var repo = SecurityScanControllerIT.this.createRepo(RepoType.MAVEN);
      SecurityScanControllerIT.this.completedScan(
          repo,
          "a",
          "unknown-only",
          at(1),
          SecurityScanControllerIT.this.findings(Severity.UNKNOWN));
      SecurityScanControllerIT.this.completedScan(
          repo,
          "a",
          "low-medium",
          at(2),
          SecurityScanControllerIT.this.findings(Severity.LOW, Severity.MEDIUM));
      SecurityScanControllerIT.this.completedScan(
          repo,
          "a",
          "all",
          at(3),
          SecurityScanControllerIT.this.findings(
              Severity.UNKNOWN, Severity.LOW, Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM));
      SecurityScanControllerIT.this.completedScan(repo, "a", "clean", at(4));

      final var body =
          expectScans(
              SecurityScanControllerIT.this.getScans(
                  SecurityScanControllerIT.this.adminBearerToken()));

      assertThat(content(body))
          .extracting(scan -> scan.get("artifactVersion"), scan -> scan.get("highestSeverity"))
          .containsExactly(
              tuple("clean", null),
              tuple("all", "CRITICAL"),
              tuple("low-medium", "MEDIUM"),
              tuple("unknown-only", "UNKNOWN"));
    }

    @Test
    @DisplayName("lists every scan of the same artifact version, not only the latest")
    void historyOfOneVersion() throws Exception {
      final var repo = SecurityScanControllerIT.this.createRepo(RepoType.MAVEN);
      SecurityScanControllerIT.this.completedScan(
          repo, "a", "1.0", at(1), SecurityScanControllerIT.this.findings(Severity.HIGH));
      SecurityScanControllerIT.this.completedScan(repo, "a", "1.0", at(2));

      final var body =
          expectScans(
              SecurityScanControllerIT.this.getScans(
                  SecurityScanControllerIT.this.adminBearerToken()));

      assertThat(content(body))
          .extracting(scan -> scan.get("highestSeverity"))
          .containsExactly(null, "HIGH");
      assertPage(body, 10, 0, 2, 1);
    }

    // -------------------------------------------------------------------------------------------
    // Filters
    // -------------------------------------------------------------------------------------------

    /**
     * Two repositories per type, and scans of every severity in each, plus a clean scan with no
     * severity at all.
     */
    private void seedFilterFixture() {
      final var mavenA = SecurityScanControllerIT.this.createRepo(RepoType.MAVEN);
      final var mavenB = SecurityScanControllerIT.this.createRepo(RepoType.MAVEN);
      final var npm = SecurityScanControllerIT.this.createRepo(RepoType.NPM);
      final var docker = SecurityScanControllerIT.this.createRepo(RepoType.DOCKER);
      final var t = SecurityScanControllerIT.this;
      t.completedScan(mavenA, "a", "mavenA-critical", at(1), t.findings(Severity.CRITICAL));
      t.completedScan(mavenA, "a", "mavenA-high", at(2), t.findings(Severity.HIGH));
      t.completedScan(mavenA, "a", "mavenA-clean", at(3));
      t.completedScan(mavenB, "a", "mavenB-high", at(4), t.findings(Severity.HIGH));
      t.completedScan(npm, "a", "npm-critical", at(5), t.findings(Severity.CRITICAL));
      t.completedScan(npm, "a", "npm-low", at(6), t.findings(Severity.LOW));
      t.completedScan(docker, "a", "docker-unknown", at(7), t.findings(Severity.UNKNOWN));
      t.completedScan(docker, "a", "docker-medium", at(8), t.findings(Severity.MEDIUM));
      this.repoNames.put("mavenA", mavenA.name());
      this.repoNames.put("mavenB", mavenB.name());
      this.repoNames.put("npm", npm.name());
      this.repoNames.put("docker", docker.name());
    }

    private final Map<String, String> repoNames = new HashMap<>();

    @ParameterizedTest(name = "severity={0}")
    @MethodSource("severityFilterExpectations")
    @DisplayName("filters by severity alone")
    void filterBySeverity(final Severity severity, final List<String> expectedVersions)
        throws Exception {
      this.seedFilterFixture();

      final var body =
          expectScans(
              SecurityScanControllerIT.this.getScans(
                  SecurityScanControllerIT.this.adminBearerToken(),
                  Map.of("severity", severity.name())));

      assertThat(artifactVersions(body)).containsExactlyElementsOf(expectedVersions);
      assertPage(body, 10, 0, expectedVersions.size(), expectedVersions.isEmpty() ? 0 : 1);
    }

    static Stream<Arguments> severityFilterExpectations() {
      return Stream.of(
          Arguments.of(Severity.CRITICAL, List.of("npm-critical", "mavenA-critical")),
          Arguments.of(Severity.HIGH, List.of("mavenB-high", "mavenA-high")),
          Arguments.of(Severity.MEDIUM, List.of("docker-medium")),
          Arguments.of(Severity.LOW, List.of("npm-low")),
          Arguments.of(Severity.UNKNOWN, List.of("docker-unknown")));
    }

    @ParameterizedTest(name = "repoType={0}")
    @MethodSource("repoTypeFilterExpectations")
    @DisplayName("filters by repoType alone")
    void filterByRepoType(final RepoType repoType, final List<String> expectedVersions)
        throws Exception {
      this.seedFilterFixture();

      final var body =
          expectScans(
              SecurityScanControllerIT.this.getScans(
                  SecurityScanControllerIT.this.adminBearerToken(),
                  Map.of("repoType", repoType.name())));

      assertThat(artifactVersions(body)).containsExactlyElementsOf(expectedVersions);
      assertThat(content(body))
          .extracting(scan -> scan.get("repoType"))
          .allSatisfy(type -> assertThat(type).isEqualTo(repoType.name()));
      assertPage(body, 10, 0, expectedVersions.size(), expectedVersions.isEmpty() ? 0 : 1);
    }

    static Stream<Arguments> repoTypeFilterExpectations() {
      return Stream.of(
          Arguments.of(
              RepoType.MAVEN,
              List.of("mavenB-high", "mavenA-clean", "mavenA-high", "mavenA-critical")),
          Arguments.of(RepoType.NPM, List.of("npm-low", "npm-critical")),
          Arguments.of(RepoType.DOCKER, List.of("docker-medium", "docker-unknown")),
          Arguments.of(RepoType.PYPI, List.of()),
          Arguments.of(RepoType.CARGO, List.of()));
    }

    @Test
    @DisplayName("filters by repoName alone")
    void filterByRepoName() throws Exception {
      this.seedFilterFixture();

      final var body =
          expectScans(
              SecurityScanControllerIT.this.getScans(
                  SecurityScanControllerIT.this.adminBearerToken(),
                  Map.of("repoName", this.repoNames.get("mavenA"))));

      assertThat(artifactVersions(body))
          .containsExactly("mavenA-clean", "mavenA-high", "mavenA-critical");
      assertThat(content(body))
          .extracting(scan -> scan.get("repoName"))
          .containsOnly(this.repoNames.get("mavenA"));
      assertPage(body, 10, 0, 3, 1);
    }

    @Test
    @DisplayName("combines severity and repoType")
    void filterBySeverityAndRepoType() throws Exception {
      this.seedFilterFixture();

      final var body =
          expectScans(
              SecurityScanControllerIT.this.getScans(
                  SecurityScanControllerIT.this.adminBearerToken(),
                  Map.of("severity", "HIGH", "repoType", "MAVEN")));

      assertThat(artifactVersions(body)).containsExactly("mavenB-high", "mavenA-high");
      assertPage(body, 10, 0, 2, 1);
    }

    @Test
    @DisplayName("combines severity and repoName")
    void filterBySeverityAndRepoName() throws Exception {
      this.seedFilterFixture();

      final var body =
          expectScans(
              SecurityScanControllerIT.this.getScans(
                  SecurityScanControllerIT.this.adminBearerToken(),
                  Map.of("severity", "HIGH", "repoName", this.repoNames.get("mavenB"))));

      assertThat(artifactVersions(body)).containsExactly("mavenB-high");
      assertPage(body, 10, 0, 1, 1);
    }

    @Test
    @DisplayName("combines repoType and repoName")
    void filterByRepoTypeAndRepoName() throws Exception {
      this.seedFilterFixture();

      final var body =
          expectScans(
              SecurityScanControllerIT.this.getScans(
                  SecurityScanControllerIT.this.adminBearerToken(),
                  Map.of("repoType", "NPM", "repoName", this.repoNames.get("npm"))));

      assertThat(artifactVersions(body)).containsExactly("npm-low", "npm-critical");
      assertPage(body, 10, 0, 2, 1);
    }

    @Test
    @DisplayName("combines all three filters")
    void filterByAllThree() throws Exception {
      this.seedFilterFixture();

      final var body =
          expectScans(
              SecurityScanControllerIT.this.getScans(
                  SecurityScanControllerIT.this.adminBearerToken(),
                  Map.of(
                      "severity",
                      "CRITICAL",
                      "repoType",
                      "MAVEN",
                      "repoName",
                      this.repoNames.get("mavenA"))));

      assertThat(artifactVersions(body)).containsExactly("mavenA-critical");
      assertPage(body, 10, 0, 1, 1);
    }

    @Test
    @DisplayName("returns an empty page when the filters contradict each other")
    void contradictingFilters() throws Exception {
      this.seedFilterFixture();

      // mavenA is a MAVEN repository, so repoType=NPM can never match it.
      final var body =
          expectScans(
              SecurityScanControllerIT.this.getScans(
                  SecurityScanControllerIT.this.adminBearerToken(),
                  Map.of("repoType", "NPM", "repoName", this.repoNames.get("mavenA"))));

      assertThat(content(body)).isEmpty();
      assertPage(body, 10, 0, 0, 0);
    }

    @Test
    @DisplayName("never matches a scan without a severity when filtering by severity")
    void severityFilterExcludesCleanScans() throws Exception {
      this.seedFilterFixture();

      for (final var severity : Severity.values()) {
        final var body =
            expectScans(
                SecurityScanControllerIT.this.getScans(
                    SecurityScanControllerIT.this.adminBearerToken(),
                    Map.of("severity", severity.name())));

        assertThat(artifactVersions(body)).doesNotContain("mavenA-clean");
      }
    }

    /**
     * Pinned current behavior: an unknown repository name is not an error, it simply matches
     * nothing, so the caller gets 200 with an empty page rather than 404.
     */
    @Test
    @DisplayName("returns 200 with an empty page for an unknown repoName (not 404)")
    void unknownRepoName() throws Exception {
      this.seedFilterFixture();

      final var body =
          expectScans(
              SecurityScanControllerIT.this.getScans(
                  SecurityScanControllerIT.this.adminBearerToken(),
                  Map.of("repoName", "no-such-repo-" + randomTag())));

      assertThat(content(body)).isEmpty();
      assertPage(body, 10, 0, 0, 0);
    }

    @Test
    @DisplayName("matches repoName exactly: neither a prefix nor another case matches")
    void repoNameIsAnExactMatch() throws Exception {
      this.seedFilterFixture();
      final var name = this.repoNames.get("mavenA");

      final var prefix =
          expectScans(
              SecurityScanControllerIT.this.getScans(
                  SecurityScanControllerIT.this.adminBearerToken(),
                  Map.of("repoName", name.substring(0, name.length() - 1))));
      final var upperCase =
          expectScans(
              SecurityScanControllerIT.this.getScans(
                  SecurityScanControllerIT.this.adminBearerToken(),
                  Map.of("repoName", name.toUpperCase(Locale.ROOT))));

      assertThat(content(prefix)).isEmpty();
      assertThat(content(upperCase)).isEmpty();
    }

    @ParameterizedTest(name = "severity={0}")
    @MethodSource("invalidEnumValues")
    @DisplayName("returns 400 validationError naming severity for a value outside the enum")
    void invalidSeverity(final String value) throws Exception {
      expectValidationError(
          SecurityScanControllerIT.this.getScans(
              SecurityScanControllerIT.this.adminBearerToken(), Map.of("severity", value)),
          "severity");
    }

    @ParameterizedTest(name = "repoType={0}")
    @MethodSource("invalidEnumValues")
    @DisplayName("returns 400 validationError naming repoType for a value outside the enum")
    void invalidRepoType(final String value) throws Exception {
      expectValidationError(
          SecurityScanControllerIT.this.getScans(
              SecurityScanControllerIT.this.adminBearerToken(), Map.of("repoType", value)),
          "repoType");
    }

    static Stream<String> invalidEnumValues() {
      // Enum binding is case-sensitive, so a lowercase spelling is rejected as well.
      return Stream.of("BOGUS", "high", "maven", "1", "%20");
    }

    // -------------------------------------------------------------------------------------------
    // Paging
    // -------------------------------------------------------------------------------------------

    /** Seeds {@code count} scans; version {@code v<i>} is created at minute {@code i}. */
    private void seedScans(final int count) {
      final var repo = SecurityScanControllerIT.this.createRepo(RepoType.MAVEN);
      IntStream.rangeClosed(1, count)
          .forEach(i -> SecurityScanControllerIT.this.completedScan(repo, "a", "v" + i, at(i)));
    }

    private static List<String> versionsDescending(final int from, final int to) {
      return IntStream.iterate(from, i -> i - 1)
          .limit(from - to + 1L)
          .mapToObj(i -> "v" + i)
          .toList();
    }

    @Test
    @DisplayName("defaults to page 0 and size 10")
    void defaultPaging() throws Exception {
      this.seedScans(25);

      final var body =
          expectScans(
              SecurityScanControllerIT.this.getScans(
                  SecurityScanControllerIT.this.adminBearerToken()));

      assertThat(artifactVersions(body)).containsExactlyElementsOf(versionsDescending(25, 16));
      assertPage(body, 10, 0, 25, 3);
    }

    @Test
    @DisplayName("walks every page: full pages first, a partial last page, then nothing")
    void pageBoundaries() throws Exception {
      this.seedScans(25);
      final var token = SecurityScanControllerIT.this.adminBearerToken();

      final var first =
          expectScans(
              SecurityScanControllerIT.this.getScans(token, Map.of("page", "0", "size", "10")));
      final var second =
          expectScans(
              SecurityScanControllerIT.this.getScans(token, Map.of("page", "1", "size", "10")));
      final var last =
          expectScans(
              SecurityScanControllerIT.this.getScans(token, Map.of("page", "2", "size", "10")));
      final var beyond =
          expectScans(
              SecurityScanControllerIT.this.getScans(token, Map.of("page", "3", "size", "10")));
      final var farBeyond =
          expectScans(
              SecurityScanControllerIT.this.getScans(token, Map.of("page", "99", "size", "10")));

      assertThat(artifactVersions(first)).containsExactlyElementsOf(versionsDescending(25, 16));
      assertPage(first, 10, 0, 25, 3);
      assertThat(artifactVersions(second)).containsExactlyElementsOf(versionsDescending(15, 6));
      assertPage(second, 10, 1, 25, 3);
      assertThat(artifactVersions(last)).containsExactlyElementsOf(versionsDescending(5, 1));
      assertPage(last, 10, 2, 25, 3);
      assertThat(content(beyond)).isEmpty();
      assertPage(beyond, 10, 3, 25, 3);
      assertThat(content(farBeyond)).isEmpty();
      assertPage(farBeyond, 10, 99, 25, 3);
    }

    @Test
    @DisplayName("honours a custom size, and a size that evenly divides the total")
    void customSize() throws Exception {
      this.seedScans(6);
      final var token = SecurityScanControllerIT.this.adminBearerToken();

      final var body =
          expectScans(SecurityScanControllerIT.this.getScans(token, Map.of("size", "3")));
      final var second =
          expectScans(
              SecurityScanControllerIT.this.getScans(token, Map.of("size", "3", "page", "1")));

      assertThat(artifactVersions(body)).containsExactly("v6", "v5", "v4");
      assertPage(body, 3, 0, 6, 2);
      assertThat(artifactVersions(second)).containsExactly("v3", "v2", "v1");
      assertPage(second, 3, 1, 6, 2);
    }

    @Test
    @DisplayName("returns everything on one page when size exceeds the total")
    void sizeLargerThanTotal() throws Exception {
      this.seedScans(3);

      final var body =
          expectScans(
              SecurityScanControllerIT.this.getScans(
                  SecurityScanControllerIT.this.adminBearerToken(), Map.of("size", "100")));

      assertThat(artifactVersions(body)).containsExactly("v3", "v2", "v1");
      assertPage(body, 100, 0, 3, 1);
    }

    @Test
    @DisplayName("pages within a filtered result")
    void pagingWithFilter() throws Exception {
      final var maven = SecurityScanControllerIT.this.createRepo(RepoType.MAVEN);
      final var npm = SecurityScanControllerIT.this.createRepo(RepoType.NPM);
      for (var i = 1; i <= 5; i++) {
        SecurityScanControllerIT.this.completedScan(
            maven, "a", "m" + i, at(2 * i), SecurityScanControllerIT.this.findings(Severity.HIGH));
        SecurityScanControllerIT.this.completedScan(
            npm,
            "a",
            "n" + i,
            at(2 * i + 1),
            SecurityScanControllerIT.this.findings(Severity.HIGH));
      }

      final var body =
          expectScans(
              SecurityScanControllerIT.this.getScans(
                  SecurityScanControllerIT.this.adminBearerToken(),
                  Map.of("repoType", "MAVEN", "size", "2", "page", "1")));

      assertThat(artifactVersions(body)).containsExactly("m3", "m2");
      assertPage(body, 2, 1, 5, 3);
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("nonNumericPagingParams")
    @DisplayName("returns 400 validationError naming the parameter when it is not a number")
    void nonNumericPagingParam(final String param, final String value) throws Exception {
      expectValidationError(
          SecurityScanControllerIT.this.getScans(
              SecurityScanControllerIT.this.adminBearerToken(), Map.of(param, value)),
          param);
    }

    static Stream<Arguments> nonNumericPagingParams() {
      return Stream.of(
          Arguments.of("page", "abc"),
          Arguments.of("size", "abc"),
          Arguments.of("page", "1.5"),
          Arguments.of("size", "1.5"));
    }

    /**
     * RPS-848 made the controller's {@code @Min}/{@code @Max} on {@code page}/{@code size} take
     * effect, so an out-of-range value is a 400 validationError naming the parameter (it used to
     * surface as a 500 from {@code PageRequest.of}).
     */
    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("outOfRangePagingParams")
    @DisplayName("returns 400 validationError naming the parameter when it is out of range")
    void outOfRangePagingParam(final String param, final String value) throws Exception {
      expectValidationError(
          SecurityScanControllerIT.this.getScans(
              SecurityScanControllerIT.this.adminBearerToken(), Map.of(param, value)),
          param);
    }

    static Stream<Arguments> outOfRangePagingParams() {
      return Stream.of(
          Arguments.of("page", "-1"),
          Arguments.of("size", "0"),
          Arguments.of("size", "-1"),
          // The declared maximum page size is 100.
          Arguments.of("size", "101"));
    }

    @Test
    @DisplayName("accepts the boundary values page=0 and size=100 (and size=1)")
    void pagingBoundaryValuesAreAccepted() throws Exception {
      this.seedScans(3);
      final var token = SecurityScanControllerIT.this.adminBearerToken();

      final var maxSize =
          expectScans(
              SecurityScanControllerIT.this.getScans(token, Map.of("page", "0", "size", "100")));
      final var minSize =
          expectScans(
              SecurityScanControllerIT.this.getScans(token, Map.of("page", "0", "size", "1")));

      assertThat(artifactVersions(maxSize)).containsExactly("v3", "v2", "v1");
      assertPage(maxSize, 100, 0, 3, 1);
      assertThat(artifactVersions(minSize)).containsExactly("v3");
      assertPage(minSize, 1, 0, 3, 3);
    }

    // -------------------------------------------------------------------------------------------
    // Query count (RPS-909)
    // -------------------------------------------------------------------------------------------

    /** Seeds one scan in each of {@code repos}, newest last, and returns the repos. */
    private List<TestRepo> seedScansAcross(final List<TestRepo> repos) {
      IntStream.range(0, repos.size())
          .forEach(
              i -> SecurityScanControllerIT.this.completedScan(repos.get(i), "a", "v" + i, at(i)));
      return repos;
    }

    private List<TestRepo> createRepos(final int count, final RepoType type) {
      return IntStream.range(0, count)
          .mapToObj(i -> SecurityScanControllerIT.this.createRepo(type))
          .toList();
    }

    /** Runs the request and returns how many JDBC statements Hibernate prepared for it. */
    private long statementsFor(final String token, final Map<String, String> params)
        throws Exception {
      final var statistics = SecurityScanControllerIT.this.hibernateStatistics();
      statistics.clear();
      expectScans(SecurityScanControllerIT.this.getScans(token, params));
      assertThat(statistics.getEntityStatistics(Repo.class.getName()).getFetchCount())
          .as("repositories loaded lazily, one query each")
          .isZero();
      return statistics.getPrepareStatementCount();
    }

    @Test
    @DisplayName("loads a page of scans from many repositories in a constant number of queries")
    void constantQueryCountAcrossRepositories() throws Exception {
      final var token = SecurityScanControllerIT.this.adminBearerToken();
      final var params = Map.of("size", "10");

      final var oneRepo = SecurityScanControllerIT.this.createRepo(RepoType.MAVEN);
      IntStream.range(0, 10)
          .forEach(i -> SecurityScanControllerIT.this.completedScan(oneRepo, "a", "v" + i, at(i)));
      final var singleRepoStatements = this.statementsFor(token, params);

      SecurityScanControllerIT.this.scanRepository.deleteAll();
      final var repos = this.seedScansAcross(this.createRepos(10, RepoType.MAVEN));
      final var manyReposStatements = this.statementsFor(token, params);

      final var body = expectScans(SecurityScanControllerIT.this.getScans(token, params));
      assertThat(content(body))
          .extracting(scan -> scan.get("repoName"), scan -> scan.get("repoType"))
          .containsExactlyElementsOf(
              repos.reversed().stream().map(r -> tuple(r.name(), r.type().name())).toList());

      // The caller lookup, the page query and the count query, whatever the repositories.
      assertThat(manyReposStatements).isEqualTo(singleRepoStatements).isEqualTo(3);
    }

    @Test
    @DisplayName("keeps the query count constant when a repository filter applies")
    void constantQueryCountWithFilter() throws Exception {
      final var token = SecurityScanControllerIT.this.adminBearerToken();
      this.seedScansAcross(this.createRepos(10, RepoType.NPM));
      SecurityScanControllerIT.this.completedScan(
          SecurityScanControllerIT.this.createRepo(RepoType.MAVEN), "a", "other", at(100));

      final var statements =
          this.statementsFor(token, Map.of("size", "5", "repoType", RepoType.NPM.name()));

      final var body =
          expectScans(
              SecurityScanControllerIT.this.getScans(
                  token, Map.of("size", "5", "repoType", RepoType.NPM.name())));
      assertThat(content(body))
          .hasSize(5)
          .allSatisfy(s -> assertThat(s).containsEntry("repoType", "NPM"));
      assertPage(body, 5, 0, 10, 2);
      assertThat(statements).isEqualTo(3);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // GET /api/security/scans/summary
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("GET /api/security/scans/summary")
  class Summary {

    @Test
    @DisplayName("returns all-zero counts when there are no scans")
    void noScans() throws Exception {
      final var body =
          expectSummary(
              SecurityScanControllerIT.this.getSummary(
                  SecurityScanControllerIT.this.adminBearerToken()));

      assertSummary(body, 0, 0, 0, 0, 0);
    }

    @Test
    @DisplayName("returns all-zero counts for scans that produced no findings")
    void scansWithoutFindings() throws Exception {
      final var repo = SecurityScanControllerIT.this.createRepo(RepoType.MAVEN);
      SecurityScanControllerIT.this.completedScan(repo, "a", "clean", at(1));
      SecurityScanControllerIT.this.failedScan(repo, "a", "failed", at(2));

      final var body =
          expectSummary(
              SecurityScanControllerIT.this.getSummary(
                  SecurityScanControllerIT.this.adminBearerToken()));

      assertSummary(body, 0, 0, 0, 0, 0);
    }

    @Test
    @DisplayName("counts findings per severity across repositories, types and scan statuses")
    void mixedStatusesAndSeverities() throws Exception {
      final var t = SecurityScanControllerIT.this;
      final var maven = t.createRepo(RepoType.MAVEN);
      final var npm = t.createRepo(RepoType.NPM);
      final var docker = t.createRepo(RepoType.DOCKER);
      t.completedScan(
          maven, "a", "1", at(1), t.findings(Severity.CRITICAL, Severity.CRITICAL, Severity.HIGH));
      t.completedScan(maven, "a", "2", at(2), t.findings(Severity.MEDIUM, Severity.LOW));
      t.completedScan(npm, "b", "1", at(3), t.findings(Severity.HIGH, Severity.UNKNOWN));
      t.completedScan(docker, "c", "1", at(4), t.findings(Severity.LOW, Severity.LOW));
      // Scans that have not produced (or never will produce) findings add nothing.
      t.failedScan(npm, "b", "2", at(5));
      t.pendingScan(docker, "c", "2", at(6));
      t.runningScan(maven, "a", "3", at(7));

      final var body = expectSummary(t.getSummary(t.adminBearerToken()));

      assertSummary(body, 2, 2, 1, 3, 1);
      assertThat((Map<String, Object>) JsonPath.read(body, "$.data"))
          .containsEntry("totalCount", 9);
    }

    @Test
    @DisplayName("counts only the latest completed scan of each artifact version")
    void supersededScansAreIgnored() throws Exception {
      final var t = SecurityScanControllerIT.this;
      final var repo = t.createRepo(RepoType.MAVEN);
      // 1.0 was rescanned: only the newer result counts.
      t.completedScan(repo, "a", "1.0", at(1), t.findings(Severity.CRITICAL, Severity.CRITICAL));
      t.completedScan(repo, "a", "1.0", at(2), t.findings(Severity.LOW));
      // 2.0 was rescanned and the newer scan failed: the earlier completed scan's findings still
      // apply.
      t.completedScan(repo, "a", "2.0", at(3), t.findings(Severity.HIGH));
      t.failedScan(repo, "a", "2.0", at(4));
      // 3.0 has a single scan.
      t.completedScan(repo, "a", "3.0", at(5), t.findings(Severity.MEDIUM));

      final var body = expectSummary(t.getSummary(t.adminBearerToken()));

      assertSummary(body, 0, 1, 1, 1, 0);
    }

    @Test
    @DisplayName("keeps the last completed findings while a rescan is not completed")
    void unfinishedRescansKeepTheLastKnownFindings() throws Exception {
      final var t = SecurityScanControllerIT.this;
      final var repo = t.createRepo(RepoType.MAVEN);
      final var artifact = "a";

      // Pending rescan: earlier completed scan's findings still count.
      t.completedScan(repo, artifact, "pending", at(1), t.findings(Severity.HIGH));
      t.pendingScan(repo, artifact, "pending", at(2));

      // Queued rescan: earlier completed scan's findings still count.
      t.completedScan(repo, artifact, "queued", at(1), t.findings(Severity.HIGH));
      t.queuedScan(repo, artifact, "queued", at(2));

      // Running rescan: earlier completed scan's findings still count.
      t.completedScan(repo, artifact, "running", at(1), t.findings(Severity.HIGH));
      t.runningScan(repo, artifact, "running", at(2));

      // Failed rescan: earlier completed scan's findings still count.
      t.completedScan(repo, artifact, "failed", at(1), t.findings(Severity.HIGH));
      t.failedScan(repo, artifact, "failed", at(2));

      // Only failed scans, no completed: contributes nothing.
      t.failedScan(repo, artifact, "never-completed", at(3));

      // Multiple completed scans: only the latest one counts.
      t.completedScan(
          repo, artifact, "completed-after-failure", at(1), t.findings(Severity.CRITICAL));
      t.failedScan(repo, artifact, "completed-after-failure", at(2));
      t.completedScan(repo, artifact, "completed-after-failure", at(3), t.findings(Severity.LOW));

      final var body = expectSummary(t.getSummary(t.adminBearerToken()));

      assertSummary(body, 0, 4, 0, 1, 0);
    }

    @Test
    @DisplayName("counts the same version once per repository")
    void sameVersionInTwoRepositories() throws Exception {
      final var t = SecurityScanControllerIT.this;
      final var first = t.createRepo(RepoType.MAVEN);
      final var second = t.createRepo(RepoType.MAVEN);
      t.completedScan(first, "a", "1.0", at(1), t.findings(Severity.HIGH));
      t.completedScan(second, "a", "1.0", at(2), t.findings(Severity.HIGH));

      final var body = expectSummary(t.getSummary(t.adminBearerToken()));

      assertSummary(body, 0, 2, 0, 0, 0);
    }

    private TestRepo[] seedFilterFixture() {
      final var t = SecurityScanControllerIT.this;
      final var mavenA = t.createRepo(RepoType.MAVEN);
      final var mavenB = t.createRepo(RepoType.MAVEN);
      final var npm = t.createRepo(RepoType.NPM);
      t.completedScan(mavenA, "a", "1", at(1), t.findings(Severity.CRITICAL, Severity.HIGH));
      t.completedScan(mavenB, "a", "1", at(2), t.findings(Severity.HIGH, Severity.LOW));
      t.completedScan(npm, "a", "1", at(3), t.findings(Severity.MEDIUM, Severity.UNKNOWN));
      return new TestRepo[] {mavenA, mavenB, npm};
    }

    @Test
    @DisplayName("filters by repoType")
    void filterByRepoType() throws Exception {
      final var t = SecurityScanControllerIT.this;
      this.seedFilterFixture();

      final var maven =
          expectSummary(t.getSummary(t.adminBearerToken(), Map.of("repoType", "MAVEN")));
      final var npm = expectSummary(t.getSummary(t.adminBearerToken(), Map.of("repoType", "NPM")));
      final var docker =
          expectSummary(t.getSummary(t.adminBearerToken(), Map.of("repoType", "DOCKER")));

      assertSummary(maven, 1, 2, 0, 1, 0);
      assertSummary(npm, 0, 0, 1, 0, 1);
      assertSummary(docker, 0, 0, 0, 0, 0);
    }

    @Test
    @DisplayName("filters by repoName")
    void filterByRepoName() throws Exception {
      final var t = SecurityScanControllerIT.this;
      final var repos = this.seedFilterFixture();

      final var mavenA =
          expectSummary(t.getSummary(t.adminBearerToken(), Map.of("repoName", repos[0].name())));
      final var mavenB =
          expectSummary(t.getSummary(t.adminBearerToken(), Map.of("repoName", repos[1].name())));
      final var npm =
          expectSummary(t.getSummary(t.adminBearerToken(), Map.of("repoName", repos[2].name())));

      assertSummary(mavenA, 1, 1, 0, 0, 0);
      assertSummary(mavenB, 0, 1, 0, 1, 0);
      assertSummary(npm, 0, 0, 1, 0, 1);
    }

    @Test
    @DisplayName("combines repoType and repoName")
    void filterByRepoTypeAndRepoName() throws Exception {
      final var t = SecurityScanControllerIT.this;
      final var repos = this.seedFilterFixture();

      final var match =
          expectSummary(
              t.getSummary(
                  t.adminBearerToken(), Map.of("repoType", "MAVEN", "repoName", repos[0].name())));
      final var contradiction =
          expectSummary(
              t.getSummary(
                  t.adminBearerToken(), Map.of("repoType", "NPM", "repoName", repos[0].name())));

      assertSummary(match, 1, 1, 0, 0, 0);
      assertSummary(contradiction, 0, 0, 0, 0, 0);
    }

    /** Pinned current behavior: an unknown repository name is 200 with zero counts, not 404. */
    @Test
    @DisplayName("returns zero counts (not 404) for an unknown repoName")
    void unknownRepoName() throws Exception {
      final var t = SecurityScanControllerIT.this;
      this.seedFilterFixture();

      final var body =
          expectSummary(
              t.getSummary(
                  t.adminBearerToken(), Map.of("repoName", "no-such-repo-" + randomTag())));

      assertSummary(body, 0, 0, 0, 0, 0);
    }

    @Test
    @DisplayName("has no severity or paging parameters: unknown parameters are ignored")
    void unknownParametersAreIgnored() throws Exception {
      final var t = SecurityScanControllerIT.this;
      this.seedFilterFixture();

      final var body =
          expectSummary(
              t.getSummary(
                  t.adminBearerToken(), Map.of("severity", "CRITICAL", "page", "5", "size", "1")));

      assertSummary(body, 1, 2, 1, 1, 1);
    }

    @ParameterizedTest(name = "repoType={0}")
    @EnumSource(RepoType.class)
    @DisplayName("accepts every repoType value")
    void everyRepoTypeIsAccepted(final RepoType repoType) throws Exception {
      final var t = SecurityScanControllerIT.this;

      final var body =
          expectSummary(t.getSummary(t.adminBearerToken(), Map.of("repoType", repoType.name())));

      assertSummary(body, 0, 0, 0, 0, 0);
    }

    @ParameterizedTest(name = "repoType={0}")
    @MethodSource("invalidRepoTypes")
    @DisplayName("returns 400 validationError naming repoType for a value outside the enum")
    void invalidRepoType(final String value) throws Exception {
      final var t = SecurityScanControllerIT.this;

      expectValidationError(
          t.getSummary(t.adminBearerToken(), Map.of("repoType", value)), "repoType");
    }

    static Stream<String> invalidRepoTypes() {
      return Stream.of("BOGUS", "maven", "1");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // GET /api/security/supported-repo-types
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("GET /api/security/supported-repo-types")
  class SupportedRepoTypes {

    private void assertSupportedTypes(final String body) {
      final List<String> types = JsonPath.read(body, "$.data");
      assertThat(types).containsExactlyInAnyOrderElementsOf(STUB_SCANNER_REPO_TYPES);
    }

    @Test
    @DisplayName("lists the repo types the registry's scanners declare, never the * wildcard")
    void exactList() throws Exception {
      final var body =
          expectSupportedRepoTypes(
              SecurityScanControllerIT.this.perform(get(SUPPORTED_REPO_TYPES_PATH)));

      assertSupportedTypes(body);
      // The response is a plain JSON array of strings, not wrapped in an object.
      assertThat((Object) JsonPath.read(body, "$.data")).isInstanceOf(List.class);
      assertThat(SecurityScanControllerIT.this.scannerRegistry.getSupportedRepoTypes())
          .isEqualTo(STUB_SCANNER_REPO_TYPES);
    }

    @Test
    @DisplayName("only reports names that are valid RepoType values")
    void everyTypeIsARepoType() throws Exception {
      final var body =
          expectSupportedRepoTypes(
              SecurityScanControllerIT.this.perform(get(SUPPORTED_REPO_TYPES_PATH)));

      final List<String> types = JsonPath.read(body, "$.data");
      assertThat(types).allSatisfy(type -> assertThat(RepoType.valueOf(type)).isNotNull());
    }

    @Test
    @DisplayName("works without any Authorization header")
    void withoutToken() throws Exception {
      assertSupportedTypes(
          expectSupportedRepoTypes(
              SecurityScanControllerIT.this.perform(get(SUPPORTED_REPO_TYPES_PATH))));
    }

    /**
     * Pinned current behavior: the endpoint never reads {@code Authorization}, so even a garbage or
     * expired token is ignored rather than rejected.
     */
    @ParameterizedTest(name = "Authorization: {0}")
    @MethodSource("ignoredAuthorizationHeaders")
    @DisplayName("ignores an invalid Authorization header instead of rejecting it")
    void withInvalidToken(final String header) throws Exception {
      assertSupportedTypes(
          expectSupportedRepoTypes(
              SecurityScanControllerIT.this.perform(
                  get(SUPPORTED_REPO_TYPES_PATH).header(AUTHORIZATION, header))));
    }

    static Stream<String> ignoredAuthorizationHeaders() {
      return Stream.of("Bearer not-a-jwt", "Basic dXNlcjpw", "");
    }

    @Test
    @DisplayName("ignores an expired token")
    void withExpiredToken() throws Exception {
      final var token = SecurityScanControllerIT.this.expiredBearerTokenFor(SEEDED_ADMIN_USERNAME);

      assertSupportedTypes(
          expectSupportedRepoTypes(
              SecurityScanControllerIT.this.perform(
                  get(SUPPORTED_REPO_TYPES_PATH).header(AUTHORIZATION, token))));
    }

    @Test
    @DisplayName("is the same for a valid admin, a valid non-admin and an anonymous caller")
    void sameForEveryCaller() throws Exception {
      final var t = SecurityScanControllerIT.this;
      final var nonAdmin = t.bearerTokenFor(t.createUser(UserRole.USER));

      final var admin =
          expectSupportedRepoTypes(
              t.perform(
                  get(SUPPORTED_REPO_TYPES_PATH).header(AUTHORIZATION, t.adminBearerToken())));
      final var user =
          expectSupportedRepoTypes(
              t.perform(get(SUPPORTED_REPO_TYPES_PATH).header(AUTHORIZATION, nonAdmin)));
      final var anonymous = expectSupportedRepoTypes(t.perform(get(SUPPORTED_REPO_TYPES_PATH)));

      assertSupportedTypes(admin);
      assertSupportedTypes(user);
      assertSupportedTypes(anonymous);
    }

    /**
     * With {@code repsy.security.scanner} disabled (the default in the open-source distribution)
     * only the wildcard scanner is registered and the registry reports no repo types at all.
     */
    @Test
    @DisplayName("returns an empty list when the registry reports no supported types")
    void noSupportedTypes() throws Exception {
      doReturn(Set.of())
          .when(SecurityScanControllerIT.this.scannerRegistry)
          .getSupportedRepoTypes();

      final var body =
          expectSupportedRepoTypes(
              SecurityScanControllerIT.this.perform(get(SUPPORTED_REPO_TYPES_PATH)));

      assertThat((List<?>) JsonPath.read(body, "$.data")).isEmpty();
    }

    @Test
    @DisplayName("reflects whatever the registry currently reports, one entry per type")
    void reflectsRegistry() throws Exception {
      doReturn(Set.of("CARGO"))
          .when(SecurityScanControllerIT.this.scannerRegistry)
          .getSupportedRepoTypes();

      final var body =
          expectSupportedRepoTypes(
              SecurityScanControllerIT.this.perform(get(SUPPORTED_REPO_TYPES_PATH)));

      assertThat((List<String>) JsonPath.read(body, "$.data")).containsExactly("CARGO");
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
     * answers with 404 {@code itemNotFound} (RPS-849). The answer is the same with and without a
     * valid admin token, because no handler is ever reached.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedMethods")
    @DisplayName("answers 404 itemNotFound for a verb the path does not map")
    void unsupportedMethod(final String name, final MockHttpServletRequestBuilder request)
        throws Exception {
      final var token = SecurityScanControllerIT.this.adminBearerToken();

      expectError(
          SecurityScanControllerIT.this.perform(request.header(AUTHORIZATION, token)),
          HttpStatus.NOT_FOUND,
          "itemNotFound",
          null,
          "The requested item is not found.");
    }

    @ParameterizedTest(name = "{0} without a token")
    @MethodSource("unsupportedMethods")
    @DisplayName("answers 404 itemNotFound for an unmapped verb even without a token")
    void unsupportedMethodWithoutToken(
        final String name, final MockHttpServletRequestBuilder request) throws Exception {
      expectError(
          SecurityScanControllerIT.this.perform(request),
          HttpStatus.NOT_FOUND,
          "itemNotFound",
          null,
          "The requested item is not found.");
    }

    static Stream<Arguments> unsupportedMethods() {
      return Stream.of(SCANS_PATH, SUMMARY_PATH, SUPPORTED_REPO_TYPES_PATH)
          .flatMap(
              path ->
                  Stream.of(
                      Arguments.of("POST " + path, post(path)),
                      Arguments.of("PUT " + path, put(path)),
                      Arguments.of("PATCH " + path, patch(path)),
                      Arguments.of("DELETE " + path, delete(path))));
    }
  }
}
