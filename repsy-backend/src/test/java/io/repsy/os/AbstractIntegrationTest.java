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
package io.repsy.os;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.jayway.jsonpath.JsonPath;
import io.repsy.os.shared.auth.services.RefreshTokenService;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.repositories.UserRepository;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.TemporalAmount;
import java.util.Base64;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for full-stack integration tests that drive the real Spring context, MVC dispatch and
 * a containerized PostgreSQL database (Flyway-migrated) through {@link MockMvc}.
 *
 * <p>It bundles what every such suite needs, so a new one only has to extend it:
 *
 * <ul>
 *   <li>a <em>single</em> {@code postgres:18} container per JVM, wired in through {@link
 *       ServiceConnection}. It is started once in a static initializer instead of per class via
 *       {@code @Container}: subclasses share one cached Spring context, and a per-class container
 *       would leave that cached context pointing at a stopped database. Testcontainers' Ryuk reaper
 *       removes it when the JVM exits;
 *   <li>one temporary {@code storage-gateway.fs.base-path} shared by all subclasses. Tests never
 *       clean it up, so give every repo a unique name (see {@link #uniqueRepoName});
 *   <li>{@link #apiPort()}: {@code PortBasedRequestMappingHandlerMapping} buckets handlers by the
 *       request's local port and {@link MockMvc#perform} defaults it to 80, which matches nothing,
 *       so every request must fake the local port onto {@code multiport.ports.api} (8080);
 *   <li>{@link #protocolPort()}: the same for wire-protocol requests, which the protocol router
 *       serves on the main port (9090) and resolves from the servlet path (RPS-903);
 *   <li>user, JWT and repo seeding helpers plus assertions for the {@code RestResponse} envelope.
 * </ul>
 *
 * <p>Every test method runs in one transaction that is rolled back afterwards, so the only data
 * that survives between tests is what the application seeds at startup (the {@code admin} user,
 * whose password is {@link #SEEDED_ADMIN_PASSWORD}). The same transaction is visible to MockMvc
 * requests because they run on the test thread.
 *
 * <p>The database is shared by every subclass, so a class that has to commit data (an
 * {@code @Async} listener cannot see an open test transaction) opts out with
 * {@code @Transactional(propagation = Propagation.NOT_SUPPORTED)} and must track and delete only
 * the rows it created. Never empty a table other classes rely on, and don't assert on the absolute
 * size of one: measure it before the fixtures and compare against that.
 *
 * <p>{@link CommittedRowsGuard} enforces that: it waits for the startup seeding of the default
 * repos before the first test, snapshots the tables, and fails the class that ends with different
 * row counts (removing the rows it added, so the next class is not affected).
 *
 * <p>The one exception to the shared database is {@code DefaultRepoSeedingIT}, which has to empty
 * the {@code repo} table and re-publish {@code UserCreatedEvent}. It does not extend this class and
 * owns a container of its own; nothing else should.
 */
@AutoConfigureMockMvc
@Transactional
@ExtendWith(CommittedRowsGuard.class)
@SpringBootTest(
    classes = RepsyApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
public abstract class AbstractIntegrationTest {

  protected static final int API_PORT = 8080;

  /** The main port, where the protocol router serves the package-manager wire protocols. */
  protected static final int PROTOCOL_PORT = 9090;

  protected static final String VALID_PASSWORD = "Password1!";
  protected static final String SEEDED_ADMIN_USERNAME = "admin";
  protected static final String SEEDED_ADMIN_PASSWORD = "SeededAdmin1!";
  protected static final String UUID_PATTERN =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

  protected static final String[] ENVELOPE_KEYS = {"msgId", "type", "data", "errorCode", "text"};

  @ServiceConnection
  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18")
          .withDatabaseName("repsy")
          .withUsername("repsy")
          .withPassword("repsy123")
          // Every cached Spring context keeps its own connection pool, and a class that stubs a
          // bean gets a context of its own. PostgreSQL's default of 100 connections runs out once
          // about ten contexts are cached ("sorry, too many clients already").
          .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=300");

  /** Root of the filesystem storage; each protocol keeps its repos under {@code <root>/<type>}. */
  protected static final Path STORAGE_ROOT;

  static {
    POSTGRES.start();

    try {
      STORAGE_ROOT = Files.createTempDirectory("repsy-it-storage");
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void registerDynamicProperties(final DynamicPropertyRegistry registry) {
    registry.add("storage-gateway.fs.base-path", STORAGE_ROOT::toString);
    // The database is shared by every context of the JVM, so whichever one boots first creates the
    // admin. Without this the admin gets a random password that is only ever logged.
    registry.add("admin.initial-password", () -> SEEDED_ADMIN_PASSWORD);
  }

  @Autowired protected MockMvc mockMvc;
  @Autowired protected JwtUtils jwtUtils;
  @Autowired protected RefreshTokenService refreshTokenService;
  @Autowired protected UserTxService userTxService;
  @Autowired protected UserRepository userRepository;
  @Autowired protected RepoRepository repoRepository;
  @Autowired protected JdbcTemplate jdbcTemplate;
  @PersistenceContext protected EntityManager entityManager;

  protected void registerRefreshToken(final String token) {
    final var claims = this.jwtUtils.verifyRefreshToken(token);
    this.refreshTokenService.register(
        claims.tokenId(),
        claims.userId(),
        claims.familyId(),
        JWT.decode(token).getExpiresAtAsInstant());
  }

  /**
   * Removes the startup-seeded default repos (see {@link CommittedRowsGuard}) so a test can assert
   * against a repo table it fully controls. The delete happens inside the test transaction, so the
   * rollback restores them for the next test.
   */
  protected void deleteDefaultRepos() {
    this.repoRepository.deleteAllInBatch();
    this.entityManager.flush();
    this.entityManager.clear();
  }

  // ---------------------------------------------------------------------------------------------
  // Request helpers
  // ---------------------------------------------------------------------------------------------

  protected static RequestPostProcessor apiPort() {
    return request -> {
      request.setLocalPort(API_PORT);
      return request;
    };
  }

  /**
   * Serves the request the way the protocol port does: main port ({@link #PROTOCOL_PORT}), servlet
   * path = request URI.
   *
   * <p>The protocol path parsers resolve the repo from {@code request.getServletPath()}. MockMvc
   * leaves that empty unless the test sets it, so a request that skips this post-processor never
   * matches any handler and ends in {@code 404 unknownPath} even though the route is registered
   * (RPS-903). Use it for every wire-protocol request (push, download, index, token exchange); use
   * {@link #apiPort()} for the panel API.
   */
  protected static RequestPostProcessor protocolPort() {
    return request -> {
      request.setLocalPort(PROTOCOL_PORT);
      request.setServletPath(request.getRequestURI());
      return request;
    };
  }

  protected ResultActions perform(final MockHttpServletRequestBuilder request) throws Exception {
    return this.mockMvc.perform(request.with(apiPort()));
  }

  protected static String basicAuth(final String username, final String password) {
    final var raw = (username + ":" + password).getBytes(StandardCharsets.UTF_8);
    return "Basic " + Base64.getEncoder().encodeToString(raw);
  }

  protected static String randomTag() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }

  protected static String uniqueUsername(final String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
  }

  /** Repo names are capped at 25 characters, so keep the prefix at 12 or fewer. */
  protected static String uniqueRepoName(final String prefix) {
    return prefix + "-" + randomTag();
  }

  // ---------------------------------------------------------------------------------------------
  // User / authentication fixtures
  // ---------------------------------------------------------------------------------------------

  /**
   * Creates and returns a user with its {@code createdAt} populated. The newly persisted entity
   * sits unflushed in Hibernate's first-level cache, and {@code createdAt} is a before-execution
   * generator that only assigns a value once the INSERT is flushed, so flush before re-reading.
   */
  protected User createUser(final String username, final UserRole role) {
    final var hash = PasswordHasher.hash(VALID_PASSWORD);
    final var userInfo = this.userTxService.create(username, role, hash);
    this.entityManager.flush();
    return this.userRepository.findById(userInfo.getId()).orElseThrow();
  }

  protected String bearerTokenFor(final User user) {
    return this.bearerTokenFor(user.getId(), user.getUsername());
  }

  protected String bearerTokenFor(final UUID userId, final String username) {
    return AuthUtils.AUTH_BEARER
        + this.jwtUtils.createPanelAccessToken(userId, username, Duration.ofMinutes(30));
  }

  /** A token of the kind package managers hold (npm, Cargo, Docker), which the panel rejects. */
  protected String protocolBearerTokenFor(final User user) {
    return AuthUtils.AUTH_BEARER
        + this.jwtUtils.createProtocolToken(
            user.getId(), user.getUsername(), Duration.ofMinutes(30));
  }

  /** Creates a fresh, non-seeded ADMIN and returns a protocol token for it. */
  protected String adminProtocolBearerToken() {
    return this.protocolBearerTokenFor(this.createUser(uniqueUsername("admin"), UserRole.ADMIN));
  }

  /**
   * Re-issues a panel bearer header as a protocol one for the same user. Suites that seed data
   * through a wire protocol and then read it through the panel API hold one panel token, but the
   * protocol endpoints no longer take it.
   */
  protected String asProtocolBearer(final String panelBearer) {
    final var decoded = JWT.decode(panelBearer.substring(AuthUtils.AUTH_BEARER.length()));

    return AuthUtils.AUTH_BEARER
        + this.jwtUtils.createProtocolToken(
            UUID.fromString(decoded.getSubject()),
            decoded.getClaim("username").asString(),
            Duration.ofMinutes(30));
  }

  /**
   * A signed token without an {@code aud} claim, like every token issued before tokens carried a
   * realm.
   */
  protected String claimlessToken(
      final UUID userId, final String username, final TemporalAmount ttl) {
    final var secret = (String) ReflectionTestUtils.getField(this.jwtUtils, "secret");

    return JWT.create()
        .withSubject(userId.toString())
        .withClaim("username", username)
        .withExpiresAt(Instant.now().plus(ttl))
        .sign(Algorithm.HMAC512(secret));
  }

  protected String claimlessBearerTokenFor(final User user) {
    return AuthUtils.AUTH_BEARER
        + this.claimlessToken(user.getId(), user.getUsername(), Duration.ofMinutes(30));
  }

  protected String expiredBearerTokenFor(final User user) {
    return AuthUtils.AUTH_BEARER
        + this.jwtUtils.createPanelAccessToken(
            user.getId(), user.getUsername(), Duration.ofSeconds(-30));
  }

  /** Creates a fresh, non-seeded ADMIN and returns a valid bearer token for it. */
  protected String adminBearerToken() {
    return this.bearerTokenFor(this.createUser(uniqueUsername("admin"), UserRole.ADMIN));
  }

  /** Creates a fresh plain USER and returns a valid bearer token for it. */
  protected String userBearerToken() {
    return this.bearerTokenFor(this.createUser(uniqueUsername("user"), UserRole.USER));
  }

  /**
   * Deletes users a test committed, together with the refresh tokens their logins registered:
   * {@code refresh_tokens} has no foreign key to {@code users}, so deleting the user alone leaves
   * the session rows behind. For classes that run with {@code Propagation.NOT_SUPPORTED}.
   */
  protected void deleteCommittedUsers(final Collection<UUID> userIds) {
    for (final var userId : userIds) {
      this.jdbcTemplate.update("delete from refresh_tokens where user_id = ?", userId);
    }

    this.userRepository.deleteAllById(userIds);
  }

  protected User seededAdmin() {
    return this.userRepository.findByUsername(SEEDED_ADMIN_USERNAME).orElseThrow();
  }

  /**
   * Makes the seeded admin the only ADMIN of the database and returns it, so a test can exercise
   * the "last admin" guards without assuming what other classes have committed. The other admins
   * are deleted inside the test transaction, so the rollback brings them back.
   */
  protected User seededAdminAsLastAdmin() {
    final var seeded = this.seededAdmin();
    final var otherAdmins =
        this.userRepository.findAll().stream()
            .filter(
                user -> user.getRole() == UserRole.ADMIN && !user.getId().equals(seeded.getId()))
            .toList();

    this.userRepository.deleteAll(otherAdmins);
    this.entityManager.flush();

    return seeded;
  }

  // ---------------------------------------------------------------------------------------------
  // Repo fixtures
  // ---------------------------------------------------------------------------------------------

  /**
   * Creates a repo through the real {@code POST /api/repos/{repoType}} endpoint, so both the
   * database row and the storage directory exist, and returns the persisted row.
   */
  protected Repo seedRepo(
      final RepoType type, final String name, final boolean privateRepo, final String description) {

    final var body =
        description == null
            ? "{\"name\":\"%s\",\"privateRepo\":%s}".formatted(name, privateRepo)
            : "{\"name\":\"%s\",\"privateRepo\":%s,\"description\":\"%s\"}"
                .formatted(name, privateRepo, description);

    try {
      this.perform(
              post("/api/repos/" + type.name())
                  .header(HttpHeaders.AUTHORIZATION, this.adminBearerToken())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isOk());
    } catch (final Exception e) {
      throw new IllegalStateException("Could not seed repo " + name, e);
    }

    return this.reloadRepo(name);
  }

  protected Repo seedRepo(final RepoType type, final String name) {
    return this.seedRepo(type, name, false, null);
  }

  /**
   * Flushes pending changes and re-reads the row, so the result reflects what the DB stores.
   *
   * <p>The returned entity is <em>detached</em>: a snapshot of the row at this moment. A managed
   * instance would be the very object a later request mutates (the request runs in the same
   * persistence context), which would make "nothing changed" comparisons vacuous and turn {@code
   * repo.getName()} into the new name after a rename.
   */
  protected Repo reloadRepo(final String name) {
    this.entityManager.flush();
    this.entityManager.clear();

    final var repo = this.repoRepository.findByName(name).orElseThrow();
    this.entityManager.detach(repo);

    return repo;
  }

  /** Storage directory of a repo: {@code <root>/<protocol dir>/<repo id>}. */
  protected static Path storageDirOf(final Repo repo) {
    return STORAGE_ROOT.resolve(protocolDir(repo.getType())).resolve(repo.getId().toString());
  }

  protected static String protocolDir(final RepoType type) {
    return type == RepoType.GOLANG ? "golang" : type.name().toLowerCase(Locale.ROOT);
  }

  // ---------------------------------------------------------------------------------------------
  // Response assertions
  // ---------------------------------------------------------------------------------------------

  /**
   * Asserts a 200 SUCCESS envelope (exact key set, {@code errorCode} null) and returns the raw body
   * for further assertions on {@code data}.
   */
  protected static String expectSuccess(
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

  /** Asserts a complete ERROR envelope, including the generated {@code errorCode} UUID. */
  protected static void expectError(
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
}
