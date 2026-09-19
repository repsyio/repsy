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
package io.repsy.os.server.protocols.shared.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.RepsyApplication;
import io.repsy.os.server.shared.token.entities.RepoDeployToken;
import io.repsy.os.server.shared.token.repositories.RepoDeployTokenRepository;
import io.repsy.os.server.shared.token.utils.DeployTokenHash;
import io.repsy.os.server.shared.token.utils.TokenUsernameGenerator;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.PasswordGeneratorUtil;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.repositories.UserRepository;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full-stack integration tests for {@code /api/repos/{repoName}/deploy-tokens/*}, exercising the
 * real Spring context, MVC dispatch, the {@code @RepoOperation} auth interceptor and a
 * containerized PostgreSQL database (Flyway-migrated) end to end.
 *
 * <p>Follows the pattern established by {@code ProfileControllerIT} and {@code UserControllerIT}:
 * requests go through {@link MockMvc}, but {@code ProtocolDeployTokenController} is only registered
 * on the "api" multiport connector, so every request is routed through {@link #apiPort()} to fake
 * the local port onto {@code multiport.ports.api} (8080).
 *
 * <p>Every test method runs in one transaction that is rolled back afterwards, so repos, users and
 * tokens never leak between tests.
 *
 * <p>Several assertions pin behavior that is surprising rather than desirable; each is called out
 * in the test's display name or comment so a future fix shows up as a deliberate test change:
 * deploy tokens are persisted as SHA-256 hashes, {@code DeployTokenForm} has no name pattern or
 * permission field, and token names are not unique per repo.
 */
@Testcontainers
@AutoConfigureMockMvc
@Transactional
@SpringBootTest(
    classes = RepsyApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("ProtocolDeployTokenController /api/repos/{repoName}/deploy-tokens/*")
class ProtocolDeployTokenControllerIT {

  private static final int API_PORT = 8080;
  private static final String VALID_PASSWORD = "Password1!";
  private static final String UUID_PATTERN =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
  private static final String DEPLOY_TOKEN_PATTERN = "rdt-[A-Za-z0-9_-]{43}";
  private static final String DEPLOY_USERNAME_PATTERN = "repsy-deploy-token-[a-z0-9]{7}";
  private static final String VALIDATION_TEXT = "Incoming data couldn't be validated.";
  private static final String UNSUPPORTED_MEDIA_TYPE_TEXT = "Unsupported media type.";
  private static final String TOKEN_NOT_FOUND_TEXT = "Deploy token not found.";
  private static final String REPO_NOT_FOUND_TEXT = "Repository not found";
  private static final String UNAUTHORIZED_TEXT = "The user has logged in but has no permissions.";
  private static final Map<String, String> SUCCESS_TEXTS =
      Map.of(
          "tokenCreated", "Deploy token created.",
          "tokenRevoked", "Deploy token revoked.",
          "tokenRotated", "Deploy token rotated.",
          "TokenFetched", "Token fetched.");
  private static final Instant BASE_TIME = Instant.parse("2026-01-01T00:00:00Z");

  private static final String[] ENVELOPE_KEYS = {"msgId", "type", "data", "errorCode", "text"};
  private static final String[] TOKEN_INFO_KEYS = {"token", "username"};
  private static final String[] LIST_ITEM_KEYS = {
    "id", "name", "username", "description", "read_only", "expiration_date", "created_at"
  };
  private static final String[] PAGE_KEYS = {"size", "number", "totalElements", "totalPages"};

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18")
          .withDatabaseName("repsy")
          .withUsername("repsy")
          .withPassword("repsy123");

  @DynamicPropertySource
  static void registerDynamicProperties(final DynamicPropertyRegistry registry) {
    registry.add("storage-gateway.fs.base-path", ProtocolDeployTokenControllerIT::tempStoragePath);
  }

  private static String tempStoragePath() {
    try {
      return Files.createTempDirectory("repsy-deploy-tokens-it").toString();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtils jwtUtils;
  @Autowired private UserTxService userTxService;
  @Autowired private UserRepository userRepository;
  @Autowired private RepoTxService repoTxService;
  @Autowired private RepoDeployTokenRepository deployTokenRepository;
  @PersistenceContext private EntityManager entityManager;

  // ---------------------------------------------------------------------------------------------
  // Request / fixture helpers
  // ---------------------------------------------------------------------------------------------

  private static RequestPostProcessor apiPort() {
    return request -> {
      request.setLocalPort(API_PORT);
      return request;
    };
  }

  private static String uniqueName(final String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
  }

  private static String form(final String name) {
    return "{\"name\":\"%s\"}".formatted(name);
  }

  private ResultActions perform(final MockHttpServletRequestBuilder request) throws Exception {
    return this.mockMvc.perform(request.with(apiPort()));
  }

  private static String tokensUrl(final String repoName) {
    return "/api/repos/" + repoName + "/deploy-tokens";
  }

  private static String tokensUrl(final RepoInfo repo) {
    return tokensUrl(repo.getName());
  }

  private static String tokenUrl(final String repoName, final Object tokenId) {
    return tokensUrl(repoName) + "/" + tokenId;
  }

  private static String tokenUrl(final RepoInfo repo, final Object tokenId) {
    return tokenUrl(repo.getName(), tokenId);
  }

  private RepoInfo createRepo(final RepoType type) {
    return this.repoTxService.createRepo(uniqueName("repo"), type, false, null);
  }

  private User createUser(final String username, final UserRole role) {
    final var salt = PasswordGeneratorUtil.generateSalt();
    final var hash = PasswordGeneratorUtil.hashPassword(VALID_PASSWORD, salt);
    final var userInfo = this.userTxService.create(username, role, hash, salt);
    this.entityManager.flush();
    return this.userRepository.findById(userInfo.getId()).orElseThrow();
  }

  private String bearerTokenFor(final User user) {
    return this.bearerTokenFor(user.getId(), user.getUsername());
  }

  private String bearerTokenFor(final UUID userId, final String username) {
    return AuthUtils.AUTH_BEARER
        + this.jwtUtils.createPanelAccessToken(userId, username, Duration.ofMinutes(30));
  }

  private String expiredBearerTokenFor(final User user) {
    return AuthUtils.AUTH_BEARER
        + this.jwtUtils.createPanelAccessToken(
            user.getId(), user.getUsername(), Duration.ofSeconds(-30));
  }

  /** Creates a fresh, non-seeded ADMIN (the only role holding MANAGE) and returns its token. */
  private String adminBearerToken() {
    return this.bearerTokenFor(this.createUser(uniqueName("admin"), UserRole.ADMIN));
  }

  /**
   * Inserts a deploy token row directly and returns it re-read from the database. The secret is
   * generated and hashed exactly as the application does, so it satisfies the unique index on
   * {@code token}.
   */
  private RepoDeployToken seedToken(
      final RepoInfo repo,
      final String name,
      final boolean readOnly,
      final Instant expirationDate) {
    final var entity = new RepoDeployToken();
    entity.setRepo(this.repoTxService.getRepoEntity(repo.getStorageKey()));
    entity.setName(name);
    entity.setDescription("seeded " + name);
    entity.setUsername(TokenUsernameGenerator.deployTokenUsername());
    entity.setToken(DeployTokenHash.hash(TokenFactory.deployToken()));
    entity.setReadOnly(readOnly);
    entity.setExpirationDate(expirationDate);
    entity.setTokenDurationDay(30);
    this.deployTokenRepository.save(entity);
    return this.reload(entity.getId());
  }

  private RepoDeployToken seedToken(final RepoInfo repo, final String name) {
    return this.seedToken(repo, name, false, Instant.now().plus(30, ChronoUnit.DAYS));
  }

  /** Flushes pending changes and re-reads the row, so the result reflects what the DB stores. */
  private RepoDeployToken reload(final UUID tokenId) {
    this.entityManager.flush();
    this.entityManager.clear();
    return this.deployTokenRepository.findById(tokenId).orElseThrow();
  }

  /**
   * Immutable copy of a row's columns. Entities are live objects in the test's persistence context,
   * so a request that mutates a token changes the very instance the test holds; comparing against a
   * snapshot taken <em>before</em> the request avoids asserting a mutated object against itself
   * (and sidesteps Lombok's {@code equals}, which reaches into the lazy {@code repo} proxy).
   */
  private record TokenState(
      UUID id,
      UUID repoId,
      String name,
      String username,
      String description,
      String token,
      boolean readOnly,
      Instant expirationDate,
      Instant lastUsedAt,
      Instant createdAt,
      int tokenDurationDay) {

    static TokenState of(final RepoDeployToken row) {
      return new TokenState(
          row.getId(),
          row.getRepo().getId(),
          row.getName(),
          row.getUsername(),
          row.getDescription(),
          row.getToken(),
          row.isReadOnly(),
          row.getExpirationDate(),
          row.getLastUsedAt(),
          row.getCreatedAt(),
          row.getTokenDurationDay());
    }

    TokenState withToken(final String newToken) {
      return new TokenState(
          this.id,
          this.repoId,
          this.name,
          this.username,
          this.description,
          newToken,
          this.readOnly,
          this.expirationDate,
          this.lastUsedAt,
          this.createdAt,
          this.tokenDurationDay);
    }
  }

  private TokenState stateOf(final UUID tokenId) {
    return TokenState.of(this.reload(tokenId));
  }

  private List<RepoDeployToken> tokensOf(final RepoInfo repo) {
    this.entityManager.flush();
    this.entityManager.clear();
    return this.deployTokenRepository.findAll().stream()
        .filter(token -> token.getRepo().getId().equals(repo.getStorageKey()))
        .toList();
  }

  // ---------------------------------------------------------------------------------------------
  // Response helpers
  // ---------------------------------------------------------------------------------------------

  /**
   * Asserts a 200 SUCCESS envelope (exact key set, {@code errorCode} null, {@code text} resolved
   * from messages.properties) and returns the raw body for further assertions on {@code data}.
   */
  private static String expectSuccess(final ResultActions result, final String msgId)
      throws Exception {
    final var body =
        result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

    final Map<String, Object> envelope = JsonPath.read(body, "$");
    assertThat(envelope)
        .containsOnlyKeys(ENVELOPE_KEYS)
        .containsEntry("msgId", msgId)
        .containsEntry("type", "SUCCESS")
        .containsEntry("errorCode", null)
        .containsEntry("text", SUCCESS_TEXTS.get(msgId));
    return body;
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

  private static void expectUnsupportedMediaType(final ResultActions result) throws Exception {
    expectError(
        result,
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "unsupportedMediaType",
        null,
        UNSUPPORTED_MEDIA_TYPE_TEXT);
  }

  private static void expectValidationError(final ResultActions result, final String data)
      throws Exception {
    expectError(result, HttpStatus.BAD_REQUEST, "validationError", data, VALIDATION_TEXT);
  }

  private static void expectTokenNotFound(final ResultActions result) throws Exception {
    expectError(
        result, HttpStatus.NOT_FOUND, "tokenNotFound", "tokenNotFound", TOKEN_NOT_FOUND_TEXT);
  }

  private static void expectUnauthorized(final ResultActions result) throws Exception {
    expectError(result, HttpStatus.UNAUTHORIZED, "unAuthorized", "unAuthorized", UNAUTHORIZED_TEXT);
  }

  /** Asserts the complete {@code DeployTokenInfoListItem} shape against the stored row. */
  private static void assertListItemJson(
      final Map<String, Object> node, final RepoDeployToken expected) {
    assertThat(node)
        .containsOnlyKeys(LIST_ITEM_KEYS)
        .containsEntry("id", expected.getId().toString())
        .containsEntry("name", expected.getName())
        .containsEntry("username", expected.getUsername())
        .containsEntry("description", expected.getDescription())
        .containsEntry("read_only", expected.isReadOnly());
    assertThat(instantOrNull(node.get("expiration_date"))).isEqualTo(expected.getExpirationDate());
    assertThat(instantOrNull(node.get("created_at"))).isEqualTo(expected.getCreatedAt());
    assertThat(node.values()).doesNotContain(expected.getToken());
  }

  private static Instant instantOrNull(final Object json) {
    return json == null ? null : Instant.parse((String) json);
  }

  private static long number(final Object json) {
    return ((Number) json).longValue();
  }

  private static void assertPage(
      final String body,
      final long size,
      final long number,
      final long totalElements,
      final long totalPages) {
    final Map<String, Object> data = JsonPath.read(body, "$.data");
    assertThat(data).containsOnlyKeys("content", "page");

    final Map<String, Object> page = JsonPath.read(body, "$.data.page");
    assertThat(page).containsOnlyKeys(PAGE_KEYS);
    assertThat(number(page.get("size"))).as("size").isEqualTo(size);
    assertThat(number(page.get("number"))).as("number").isEqualTo(number);
    assertThat(number(page.get("totalElements"))).as("totalElements").isEqualTo(totalElements);
    assertThat(number(page.get("totalPages"))).as("totalPages").isEqualTo(totalPages);
  }

  private static List<String> namesOf(final String body) {
    final List<Map<String, Object>> content = JsonPath.read(body, "$.data.content");
    return content.stream().map(node -> (String) node.get("name")).toList();
  }

  // ---------------------------------------------------------------------------------------------
  // Authentication & authorization -- identical for every endpoint, so parameterized
  // ---------------------------------------------------------------------------------------------

  /** One row per handler; {@code request} receives the repo name and a token id to target. */
  private record Endpoint(
      String name, Function<String, Function<UUID, MockHttpServletRequestBuilder>> request) {
    @Override
    public String toString() {
      return this.name;
    }
  }

  @Nested
  @DisplayName("authentication & authorization (all endpoints)")
  class Security {

    static Stream<Endpoint> endpoints() {
      return Stream.of(
          new Endpoint("GET", repo -> _ -> get(tokensUrl(repo))),
          new Endpoint(
              "POST",
              repo ->
                  _ ->
                      post(tokensUrl(repo))
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(form(uniqueName("probe")))),
          new Endpoint("PUT", repo -> id -> put(tokenUrl(repo, id))),
          new Endpoint("DELETE", repo -> id -> delete(tokenUrl(repo, id))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("returns 401 unAuthorized when the Authorization header is missing")
    void missingAuthorizationHeader(final Endpoint endpoint) throws Exception {
      final var repo = ProtocolDeployTokenControllerIT.this.createRepo(RepoType.MAVEN);

      expectUnauthorized(
          ProtocolDeployTokenControllerIT.this.perform(
              endpoint.request().apply(repo.getName()).apply(UUID.randomUUID())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("returns 401 unAuthorized for a header that is neither Basic nor Bearer")
    void unsupportedAuthorizationScheme(final Endpoint endpoint) throws Exception {
      final var repo = ProtocolDeployTokenControllerIT.this.createRepo(RepoType.MAVEN);

      expectUnauthorized(
          ProtocolDeployTokenControllerIT.this.perform(
              endpoint
                  .request()
                  .apply(repo.getName())
                  .apply(UUID.randomUUID())
                  .header(AUTHORIZATION, "Digest abc")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("returns 401 for a malformed/garbage bearer token")
    void malformedBearerToken(final Endpoint endpoint) throws Exception {
      final var repo = ProtocolDeployTokenControllerIT.this.createRepo(RepoType.MAVEN);

      expectError(
          ProtocolDeployTokenControllerIT.this.perform(
              endpoint
                  .request()
                  .apply(repo.getName())
                  .apply(UUID.randomUUID())
                  .header(AUTHORIZATION, "Bearer not-a-jwt")),
          HttpStatus.UNAUTHORIZED,
          "accessNotAllowed",
          "accessNotAllowed",
          "Access isn't allowed.");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("returns 401 for an expired token")
    void expiredToken(final Endpoint endpoint) throws Exception {
      final var repo = ProtocolDeployTokenControllerIT.this.createRepo(RepoType.MAVEN);
      final var admin =
          ProtocolDeployTokenControllerIT.this.createUser(uniqueName("expired"), UserRole.ADMIN);

      expectError(
          ProtocolDeployTokenControllerIT.this.perform(
              endpoint
                  .request()
                  .apply(repo.getName())
                  .apply(UUID.randomUUID())
                  .header(
                      AUTHORIZATION,
                      ProtocolDeployTokenControllerIT.this.expiredBearerTokenFor(admin))),
          HttpStatus.UNAUTHORIZED,
          "sessionExpired",
          "sessionExpired",
          "Session expired.");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("returns 404 when the token's user no longer exists")
    void tokenUserNoLongerExists(final Endpoint endpoint) throws Exception {
      final var repo = ProtocolDeployTokenControllerIT.this.createRepo(RepoType.MAVEN);
      // Authentication resolves the caller by the token's username claim, not by its subject id.
      final var token =
          ProtocolDeployTokenControllerIT.this.bearerTokenFor(
              UUID.randomUUID(), uniqueName("ghost"));

      expectError(
          ProtocolDeployTokenControllerIT.this.perform(
              endpoint
                  .request()
                  .apply(repo.getName())
                  .apply(UUID.randomUUID())
                  .header(AUTHORIZATION, token)),
          HttpStatus.NOT_FOUND,
          "userNotFound",
          "userNotFound",
          "User not found.");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("returns 401 unAuthorized for a non-admin caller (no MANAGE) and changes nothing")
    void callerWithoutManagePermission(final Endpoint endpoint) throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(RepoType.MAVEN);
      final var seeded = it.seedToken(repo, uniqueName("keep"));
      final var before = it.stateOf(seeded.getId());
      final var caller = it.createUser(uniqueName("plain"), UserRole.USER);

      expectUnauthorized(
          it.perform(
              endpoint
                  .request()
                  .apply(repo.getName())
                  .apply(seeded.getId())
                  .header(AUTHORIZATION, it.bearerTokenFor(caller))));

      final var stored = it.tokensOf(repo);
      assertThat(stored).hasSize(1);
      assertThat(TokenState.of(stored.getFirst())).isEqualTo(before);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("returns 404 repoNotFound when the repo does not exist")
    void repoDoesNotExist(final Endpoint endpoint) throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;

      expectError(
          it.perform(
              endpoint
                  .request()
                  .apply(uniqueName("missing"))
                  .apply(UUID.randomUUID())
                  .header(AUTHORIZATION, it.adminBearerToken())),
          HttpStatus.NOT_FOUND,
          "repoNotFound",
          "repoNotFound",
          REPO_NOT_FOUND_TEXT);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("returns 401 unAuthorized, not 404, for a missing repo without credentials")
    void repoDoesNotExistWithoutCredentials(final Endpoint endpoint) throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;

      expectUnauthorized(
          it.perform(endpoint.request().apply(uniqueName("missing")).apply(UUID.randomUUID())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("answers a missing repo and an existing private repo identically without a header")
    void missingAndPrivateRepoAreIndistinguishableWithoutCredentials(final Endpoint endpoint)
        throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var privateRepo =
          it.repoTxService.createRepo(uniqueName("private"), RepoType.MAVEN, true, null);
      final var tokenId = UUID.randomUUID();

      final var forPrivate =
          it.perform(endpoint.request().apply(privateRepo.getName()).apply(tokenId))
              .andExpect(status().isUnauthorized())
              .andReturn()
              .getResponse()
              .getContentAsString();
      final var forMissing =
          it.perform(endpoint.request().apply(uniqueName("missing")).apply(tokenId))
              .andExpect(status().isUnauthorized())
              .andReturn()
              .getResponse()
              .getContentAsString();

      // Everything but the per-error correlation id must match.
      assertThat(forMissing.replaceAll(UUID_PATTERN, "<id>"))
          .isEqualTo(forPrivate.replaceAll(UUID_PATTERN, "<id>"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("returns 401 unAuthorized, not 404, for a missing repo and a non-admin caller")
    void repoDoesNotExistForCallerWithoutManagePermission(final Endpoint endpoint)
        throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var caller = it.createUser(uniqueName("plain"), UserRole.USER);

      expectUnauthorized(
          it.perform(
              endpoint
                  .request()
                  .apply(uniqueName("missing"))
                  .apply(UUID.randomUUID())
                  .header(AUTHORIZATION, it.bearerTokenFor(caller))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("still rejects an invalid token before revealing that the repo is missing")
    void repoDoesNotExistWithMalformedToken(final Endpoint endpoint) throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;

      expectError(
          it.perform(
              endpoint
                  .request()
                  .apply(uniqueName("missing"))
                  .apply(UUID.randomUUID())
                  .header(AUTHORIZATION, "Bearer not-a-jwt")),
          HttpStatus.UNAUTHORIZED,
          "accessNotAllowed",
          "accessNotAllowed",
          "Access isn't allowed.");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // POST /api/repos/{repoName}/deploy-tokens
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("POST /api/repos/{repoName}/deploy-tokens")
  class CreateToken {

    @ParameterizedTest(name = "{0}")
    @EnumSource(
        value = RepoType.class,
        names = {"MAVEN", "NPM", "DOCKER"})
    @DisplayName("creates a token on repos of different types and returns the full TokenInfo")
    void createsTokenPerRepoType(final RepoType type) throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(type);
      final var name = uniqueName("ci-");
      final var expiration = Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);

      final var body =
          expectSuccess(
              it.perform(
                  post(tokensUrl(repo))
                      .header(AUTHORIZATION, it.adminBearerToken())
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          "{\"name\":\"%s\",\"username\":\"deployer\",\"description\":\"for ci\","
                                  .formatted(name)
                              + "\"read_only\":true,\"expiration_date\":\"%s\"}"
                                  .formatted(expiration))),
              "tokenCreated");

      final Map<String, Object> data = JsonPath.read(body, "$.data");
      assertThat(data).containsOnlyKeys(TOKEN_INFO_KEYS).containsEntry("username", "deployer");
      assertThat((String) data.get("token")).matches(DEPLOY_TOKEN_PATTERN);

      final var stored = it.tokensOf(repo);
      assertThat(stored).hasSize(1);
      final var row = stored.getFirst();
      assertThat(row.getName()).isEqualTo(name);
      assertThat(row.getUsername()).isEqualTo("deployer");
      assertThat(row.getDescription()).isEqualTo("for ci");
      assertThat(row.isReadOnly()).isTrue();
      assertThat(row.getExpirationDate()).isEqualTo(expiration);
      assertThat(row.getTokenDurationDay()).isEqualTo(9);
      assertThat(row.getCreatedAt()).isNotNull();
      assertThat(row.getLastUsedAt()).isNull();
      assertThat(row.getId()).isNotNull();
    }

    @Test
    @DisplayName("returns the secret once; only its hash is persisted and never listed again")
    void secretIsReturnedOnceAndStoredHashed() throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(RepoType.MAVEN);
      final var token = it.adminBearerToken();

      final var created =
          expectSuccess(
              it.perform(
                  post(tokensUrl(repo))
                      .header(AUTHORIZATION, token)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(form("once"))),
              "tokenCreated");
      final String secret = JsonPath.read(created, "$.data.token");

      final var row = it.tokensOf(repo).getFirst();
      assertThat(row.getToken()).isEqualTo(DeployTokenHash.hash(secret));
      assertThat(row.getToken()).isNotEqualTo(secret);
      assertThat(it.deployTokenRepository.findByRepoIdAndToken(repo.getStorageKey(), secret))
          .isEmpty();

      final var listed =
          expectSuccess(
              it.perform(get(tokensUrl(repo)).header(AUTHORIZATION, token)), "TokenFetched");
      assertThat(listed).doesNotContain(secret);
    }

    @Test
    @DisplayName("applies defaults: generated username, 365-day expiry, read_only=false")
    void appliesDefaults() throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(RepoType.MAVEN);
      final var before = Instant.now();

      final var body =
          expectSuccess(
              it.perform(
                  post(tokensUrl(repo))
                      .header(AUTHORIZATION, it.adminBearerToken())
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(form("defaults"))),
              "tokenCreated");

      final String username = JsonPath.read(body, "$.data.username");
      assertThat(username).matches(DEPLOY_USERNAME_PATTERN);

      final var row = it.tokensOf(repo).getFirst();
      assertThat(row.getUsername()).isEqualTo(username);
      assertThat(row.getDescription()).isNull();
      assertThat(row.isReadOnly()).isFalse();
      // Expiry and duration derive from one clock reading, so the duration is exactly 365 days.
      assertThat(row.getTokenDurationDay()).isEqualTo(365);
      assertThat(row.getExpirationDate())
          .isBetween(before.plus(365, ChronoUnit.DAYS), Instant.now().plus(365, ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("treats an empty username as absent and generates one")
    void emptyUsernameIsGenerated() throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(RepoType.MAVEN);

      final var body =
          expectSuccess(
              it.perform(
                  post(tokensUrl(repo))
                      .header(AUTHORIZATION, it.adminBearerToken())
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"name\":\"blank-user\",\"username\":\"\"}")),
              "tokenCreated");

      assertThat((String) JsonPath.read(body, "$.data.username")).matches(DEPLOY_USERNAME_PATTERN);
    }

    /** {@code expiration_date} in the past is not validated; the duration is clamped to 1 day. */
    @Test
    @DisplayName("accepts an expiration date in the past and stores an already-expired token")
    void expirationInThePastIsAccepted() throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(RepoType.MAVEN);
      final var past = Instant.now().minus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);

      expectSuccess(
          it.perform(
              post(tokensUrl(repo))
                  .header(AUTHORIZATION, it.adminBearerToken())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"name\":\"past\",\"expiration_date\":\"%s\"}".formatted(past))),
          "tokenCreated");

      final var row = it.tokensOf(repo).getFirst();
      assertThat(row.getExpirationDate()).isEqualTo(past);
      assertThat(row.getTokenDurationDay()).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("boundaryForms")
    @DisplayName("accepts values exactly at the length limits")
    void acceptsBoundaryLengths(final String label, final String json) throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(RepoType.MAVEN);

      expectSuccess(
          it.perform(
              post(tokensUrl(repo))
                  .header(AUTHORIZATION, it.adminBearerToken())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json)),
          "tokenCreated");

      assertThat(it.tokensOf(repo)).hasSize(1);
    }

    static Stream<Arguments> boundaryForms() {
      return Stream.of(
          Arguments.of("1-char name", "{\"name\":\"a\"}"),
          Arguments.of("80-char name", "{\"name\":\"%s\"}".formatted("n".repeat(80))),
          Arguments.of(
              "80-char username", "{\"name\":\"u\",\"username\":\"%s\"}".formatted("u".repeat(80))),
          Arguments.of(
              "500-char description",
              "{\"name\":\"d\",\"description\":\"%s\"}".formatted("d".repeat(500))));
    }

    /**
     * {@code DeployTokenForm} declares no name pattern, so any 1..80-character string is accepted,
     * including whitespace-only and punctuation-heavy names.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unrestrictedNames")
    @DisplayName("accepts any name of valid length (the form has no name pattern)")
    void nameHasNoPattern(final String label, final String name) throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(RepoType.MAVEN);

      expectSuccess(
          it.perform(
              post(tokensUrl(repo))
                  .header(AUTHORIZATION, it.adminBearerToken())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(form(name))),
          "tokenCreated");

      assertThat(it.tokensOf(repo)).extracting(RepoDeployToken::getName).containsExactly(name);
    }

    static Stream<Arguments> unrestrictedNames() {
      return Stream.of(
          Arguments.of("spaces only", "   "),
          Arguments.of("punctuation", "my token!@#$%^&*()"),
          Arguments.of("unicode", "dağıtım-jetonu"));
    }

    @Test
    @DisplayName("allows duplicate names within one repo (no uniqueness constraint)")
    void duplicateNameInSameRepoIsAllowed() throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(RepoType.MAVEN);
      final var token = it.adminBearerToken();

      final var first =
          expectSuccess(
              it.perform(
                  post(tokensUrl(repo))
                      .header(AUTHORIZATION, token)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(form("dup"))),
              "tokenCreated");
      final var second =
          expectSuccess(
              it.perform(
                  post(tokensUrl(repo))
                      .header(AUTHORIZATION, token)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(form("dup"))),
              "tokenCreated");

      assertThat((String) JsonPath.read(first, "$.data.token"))
          .isNotEqualTo((String) JsonPath.read(second, "$.data.token"));
      assertThat(it.tokensOf(repo))
          .extracting(RepoDeployToken::getName)
          .containsExactly("dup", "dup");
    }

    @Test
    @DisplayName("allows the same name in different repos")
    void sameNameInDifferentReposIsAllowed() throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repoA = it.createRepo(RepoType.MAVEN);
      final var repoB = it.createRepo(RepoType.NPM);
      final var token = it.adminBearerToken();

      for (final var repo : List.of(repoA, repoB)) {
        expectSuccess(
            it.perform(
                post(tokensUrl(repo))
                    .header(AUTHORIZATION, token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(form("shared"))),
            "tokenCreated");
      }

      assertThat(it.tokensOf(repoA)).hasSize(1);
      assertThat(it.tokensOf(repoB)).hasSize(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    @DisplayName("returns 400 validationError and creates nothing for an invalid body")
    void invalidBody(final String label, final String json) throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(RepoType.MAVEN);

      expectValidationError(
          it.perform(
              post(tokensUrl(repo))
                  .header(AUTHORIZATION, it.adminBearerToken())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json)),
          null);

      assertThat(it.tokensOf(repo)).isEmpty();
    }

    static Stream<Arguments> invalidBodies() {
      return Stream.of(
          Arguments.of("blank name", "{\"name\":\"\"}"),
          Arguments.of("missing name", "{\"description\":\"x\"}"),
          Arguments.of("null name", "{\"name\":null}"),
          Arguments.of("name too long", "{\"name\":\"%s\"}".formatted("n".repeat(81))),
          Arguments.of(
              "username longer than the column",
              "{\"name\":\"n\",\"username\":\"%s\"}".formatted("u".repeat(81))),
          Arguments.of(
              "description too long",
              "{\"name\":\"n\",\"description\":\"%s\"}".formatted("d".repeat(501))),
          Arguments.of("read_only not a boolean", "{\"name\":\"n\",\"read_only\":\"maybe\"}"),
          Arguments.of("read_only an object", "{\"name\":\"n\",\"read_only\":{}}"),
          Arguments.of(
              "expiration_date not a date", "{\"name\":\"n\",\"expiration_date\":\"tomorrow\"}"),
          Arguments.of("empty object", "{}"),
          Arguments.of("malformed JSON", "{not-json"),
          Arguments.of("empty body", ""));
    }

    @Test
    @DisplayName("returns 415 unsupportedMediaType for an unsupported content type")
    void unsupportedContentType() throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(RepoType.MAVEN);

      expectUnsupportedMediaType(
          it.perform(
              post(tokensUrl(repo))
                  .header(AUTHORIZATION, it.adminBearerToken())
                  .contentType(MediaType.TEXT_PLAIN)
                  .content(form("plain"))));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // GET /api/repos/{repoName}/deploy-tokens
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("GET /api/repos/{repoName}/deploy-tokens")
  class ListTokens {

    @Test
    @DisplayName("returns an empty page for a repo without tokens")
    void emptyList() throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(RepoType.MAVEN);

      final var body =
          expectSuccess(
              it.perform(get(tokensUrl(repo)).header(AUTHORIZATION, it.adminBearerToken())),
              "TokenFetched");

      final List<Object> content = JsonPath.read(body, "$.data.content");
      assertThat(content).isEmpty();
      assertPage(body, 10, 0, 0, 0);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
        value = RepoType.class,
        names = {"MAVEN", "NPM", "DOCKER"})
    @DisplayName("lists tokens on repos of different types, newest id first, with every field")
    void listsFullShapeNewestFirst(final RepoType type) throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(type);
      final var expiring =
          it.seedToken(repo, "expiring", true, BASE_TIME.plus(400, ChronoUnit.DAYS));
      final var open = it.seedToken(repo, "open", false, null);
      final var plain = it.seedToken(repo, "plain");
      final var expected =
          Stream.of(expiring, open, plain)
              .sorted(Comparator.comparing(RepoDeployToken::getId).reversed())
              .toList();

      final var body =
          expectSuccess(
              it.perform(get(tokensUrl(repo)).header(AUTHORIZATION, it.adminBearerToken())),
              "TokenFetched");

      final List<Map<String, Object>> content = JsonPath.read(body, "$.data.content");
      assertThat(content).hasSize(3);
      for (var i = 0; i < expected.size(); i++) {
        final var node = content.get(i);
        final var row = expected.get(i);
        if (row.getExpirationDate() == null) {
          // expiration_date is NON_NULL: a token that never expires omits the key entirely.
          assertThat(node).doesNotContainKey("expiration_date");
          assertThat(node.keySet())
              .containsExactlyInAnyOrder(
                  "id", "name", "username", "description", "read_only", "created_at");
          assertThat(node)
              .containsEntry("id", row.getId().toString())
              .containsEntry("name", row.getName())
              .containsEntry("username", row.getUsername())
              .containsEntry("description", row.getDescription())
              .containsEntry("read_only", row.isReadOnly());
        } else {
          assertListItemJson(node, row);
        }
      }
      assertPage(body, 10, 0, 3, 1);
    }

    @Test
    @DisplayName("omits empty username/description from list items (NON_EMPTY)")
    void omitsEmptyOptionalFields() throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(RepoType.MAVEN);
      final var seeded = it.seedToken(repo, "sparse");
      seeded.setDescription(null);
      seeded.setUsername("");
      it.reload(seeded.getId());

      final var body =
          expectSuccess(
              it.perform(get(tokensUrl(repo)).header(AUTHORIZATION, it.adminBearerToken())),
              "TokenFetched");

      final List<Map<String, Object>> content = JsonPath.read(body, "$.data.content");
      assertThat(content).hasSize(1);
      assertThat(content.getFirst())
          .containsOnlyKeys("id", "name", "read_only", "expiration_date", "created_at");
    }

    @Test
    @DisplayName("does not list tokens that belong to other repos")
    void otherReposTokensAreNotListed() throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(RepoType.MAVEN);
      final var other = it.createRepo(RepoType.NPM);
      it.seedToken(repo, "mine");
      it.seedToken(other, "theirs");

      final var body =
          expectSuccess(
              it.perform(get(tokensUrl(repo)).header(AUTHORIZATION, it.adminBearerToken())),
              "TokenFetched");

      assertThat(namesOf(body)).containsExactly("mine");
      assertPage(body, 10, 0, 1, 1);
    }

    @Test
    @DisplayName("paginates with page/size and full PagedModel metadata")
    void paginates() throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(RepoType.MAVEN);
      final var token = it.adminBearerToken();
      final var seeded =
          Stream.of("t0", "t1", "t2", "t3", "t4").map(n -> it.seedToken(repo, n)).toList();
      final var names =
          seeded.stream()
              .sorted(Comparator.comparing(RepoDeployToken::getId).reversed())
              .map(RepoDeployToken::getName)
              .toList();

      final var expectedByPage =
          Map.of(0, names.subList(0, 2), 1, names.subList(2, 4), 2, names.subList(4, 5));

      for (final var entry : expectedByPage.entrySet()) {
        final var body =
            expectSuccess(
                it.perform(
                    get(tokensUrl(repo))
                        .header(AUTHORIZATION, token)
                        .param("page", String.valueOf(entry.getKey()))
                        .param("size", "2")),
                "TokenFetched");

        assertThat(namesOf(body)).as("page %d", entry.getKey()).isEqualTo(entry.getValue());
        assertPage(body, 2, entry.getKey(), 5, 3);
      }
    }

    @Test
    @DisplayName("answers a page past the end with empty content and the real totals")
    void pagePastTheEnd() throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(RepoType.MAVEN);
      it.seedToken(repo, "only");

      final var body =
          expectSuccess(
              it.perform(
                  get(tokensUrl(repo))
                      .header(AUTHORIZATION, it.adminBearerToken())
                      .param("page", "5")
                      .param("size", "2")),
              "TokenFetched");

      final List<Object> content = JsonPath.read(body, "$.data.content");
      assertThat(content).isEmpty();
      assertPage(body, 2, 5, 1, 1);
    }

    @Test
    @DisplayName("sorts by a custom property and direction")
    void customSort() throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(RepoType.MAVEN);
      final var token = it.adminBearerToken();
      it.seedToken(repo, "bravo");
      it.seedToken(repo, "alpha");
      it.seedToken(repo, "charlie");

      final var ascending =
          expectSuccess(
              it.perform(
                  get(tokensUrl(repo)).header(AUTHORIZATION, token).param("sort", "name,asc")),
              "TokenFetched");
      assertThat(namesOf(ascending)).containsExactly("alpha", "bravo", "charlie");

      final var descending =
          expectSuccess(
              it.perform(
                  get(tokensUrl(repo)).header(AUTHORIZATION, token).param("sort", "name,desc")),
              "TokenFetched");
      assertThat(namesOf(descending)).containsExactly("charlie", "bravo", "alpha");
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("invalidPagingParams")
    @DisplayName("returns 400 validationError naming the parameter for a bad page or size")
    void invalidPagingParam(final String param, final String value) throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(RepoType.MAVEN);
      it.seedToken(repo, "only");

      expectValidationError(
          it.perform(
              get(tokensUrl(repo))
                  .header(AUTHORIZATION, it.adminBearerToken())
                  .param(param, value)),
          param);
    }

    static Stream<Arguments> invalidPagingParams() {
      return Stream.of(
          Arguments.of("page", "abc"),
          Arguments.of("size", "abc"),
          Arguments.of("page", "-1"),
          Arguments.of("size", "0"),
          Arguments.of("size", "-1"),
          Arguments.of("size", "101"));
    }

    @Test
    @DisplayName("accepts the largest allowed size")
    void acceptsMaxPageSize() throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(RepoType.MAVEN);
      it.seedToken(repo, "only");

      final var body =
          expectSuccess(
              it.perform(
                  get(tokensUrl(repo))
                      .header(AUTHORIZATION, it.adminBearerToken())
                      .param("size", "100")),
              "TokenFetched");

      assertThat(namesOf(body)).containsExactly("only");
      assertPage(body, 100, 0, 1, 1);
    }

    @Test
    @DisplayName("answers 401 unAuthorized, not 400, when a bad size comes without credentials")
    void authenticationComesBeforePagingValidation() throws Exception {
      final var repo = ProtocolDeployTokenControllerIT.this.createRepo(RepoType.MAVEN);

      expectUnauthorized(
          ProtocolDeployTokenControllerIT.this.perform(get(tokensUrl(repo)).param("size", "0")));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // PUT /api/repos/{repoName}/deploy-tokens/{tokenId}  (rotate)
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("PUT /api/repos/{repoName}/deploy-tokens/{tokenId}")
  class RotateToken {

    @ParameterizedTest(name = "{0}")
    @EnumSource(
        value = RepoType.class,
        names = {"MAVEN", "NPM", "DOCKER"})
    @DisplayName("issues a new secret, invalidates the old one, and keeps id and metadata")
    void rotatesSecret(final RepoType type) throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(type);
      final var seeded =
          it.seedToken(repo, "rotate-me", true, Instant.now().plus(30, ChronoUnit.DAYS));
      final var before = it.stateOf(seeded.getId());
      final var oldSecret = before.token();

      final var body =
          expectSuccess(
              it.perform(
                  put(tokenUrl(repo, before.id())).header(AUTHORIZATION, it.adminBearerToken())),
              "tokenRotated");

      final String newSecret = JsonPath.read(body, "$.data");
      assertThat(newSecret).matches(DEPLOY_TOKEN_PATTERN).isNotEqualTo(oldSecret);

      // Only the secret changes; id, name, username, description, read_only, expiry, duration and
      // created_at all stay as they were before the request.
      final var after = it.stateOf(before.id());
      assertThat(after).isEqualTo(before.withToken(DeployTokenHash.hash(newSecret)));
      assertThat(it.deployTokenRepository.findByRepoIdAndToken(repo.getStorageKey(), oldSecret))
          .isEmpty();
      assertThat(it.deployTokenRepository.findByRepoIdAndToken(repo.getStorageKey(), newSecret))
          .isEmpty();
      assertThat(it.tokensOf(repo)).hasSize(1);
    }

    /**
     * Rotating an <em>expired</em> token is the one case where metadata does change: the username
     * is regenerated and the expiry is reset to now + the original duration.
     */
    @Test
    @DisplayName("rotating an expired token also regenerates its username and renews its expiry")
    void rotatingExpiredTokenRenewsIt() throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(RepoType.MAVEN);
      final var seeded =
          it.seedToken(repo, "expired", false, Instant.now().minus(3, ChronoUnit.DAYS));
      final var before = it.stateOf(seeded.getId());
      final var rotatedAt = Instant.now();

      expectSuccess(
          it.perform(put(tokenUrl(repo, before.id())).header(AUTHORIZATION, it.adminBearerToken())),
          "tokenRotated");

      final var after = it.stateOf(before.id());
      assertThat(after.username()).matches(DEPLOY_USERNAME_PATTERN).isNotEqualTo(before.username());
      assertThat(after.token()).isNotEqualTo(before.token());
      assertThat(after.expirationDate())
          .isAfterOrEqualTo(rotatedAt.plus(before.tokenDurationDay(), ChronoUnit.DAYS));
      // Everything except username, secret and expiry is untouched.
      assertThat(after)
          .usingRecursiveComparison()
          .ignoringFields("username", "token", "expirationDate")
          .isEqualTo(before);
    }

    @Test
    @DisplayName("only rotates the addressed token")
    void leavesOtherTokensUntouched() throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(RepoType.MAVEN);
      final var target = it.seedToken(repo, "target");
      final var bystander = it.seedToken(repo, "bystander");
      final var bystanderBefore = it.stateOf(bystander.getId());

      expectSuccess(
          it.perform(
              put(tokenUrl(repo, target.getId())).header(AUTHORIZATION, it.adminBearerToken())),
          "tokenRotated");

      assertThat(it.stateOf(bystanderBefore.id())).isEqualTo(bystanderBefore);
    }

    @Test
    @DisplayName("returns 404 tokenNotFound for an unknown token id")
    void unknownToken() throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(RepoType.MAVEN);

      expectTokenNotFound(
          it.perform(
              put(tokenUrl(repo, UUID.randomUUID())).header(AUTHORIZATION, it.adminBearerToken())));
    }

    @Test
    @DisplayName("returns 404 tokenNotFound for a token of another repo and leaves it unchanged")
    void tokenOfAnotherRepo() throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(RepoType.MAVEN);
      final var other = it.createRepo(RepoType.NPM);
      final var foreign = it.seedToken(other, "foreign");
      final var foreignBefore = it.stateOf(foreign.getId());

      expectTokenNotFound(
          it.perform(
              put(tokenUrl(repo, foreignBefore.id()))
                  .header(AUTHORIZATION, it.adminBearerToken())));

      assertThat(it.stateOf(foreignBefore.id())).isEqualTo(foreignBefore);
    }

    @Test
    @DisplayName("returns 400 validationError naming tokenId when the id is not a UUID")
    void malformedTokenId() throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(RepoType.MAVEN);

      expectValidationError(
          it.perform(
              put(tokenUrl(repo, "not-a-uuid")).header(AUTHORIZATION, it.adminBearerToken())),
          "tokenId");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // DELETE /api/repos/{repoName}/deploy-tokens/{tokenId}  (revoke)
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("DELETE /api/repos/{repoName}/deploy-tokens/{tokenId}")
  class RevokeToken {

    @ParameterizedTest(name = "{0}")
    @EnumSource(
        value = RepoType.class,
        names = {"MAVEN", "NPM", "DOCKER"})
    @DisplayName("removes the row and returns a null-data success envelope")
    void revokesToken(final RepoType type) throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(type);
      final var target = it.seedToken(repo, "revoke-me");
      final var bystander = it.seedToken(repo, "keep-me");
      final var targetBefore = it.stateOf(target.getId());
      final var bystanderBefore = it.stateOf(bystander.getId());

      final var body =
          expectSuccess(
              it.perform(
                  delete(tokenUrl(repo, targetBefore.id()))
                      .header(AUTHORIZATION, it.adminBearerToken())),
              "tokenRevoked");

      final Object data = JsonPath.read(body, "$.data");
      assertThat(data).isNull();
      it.entityManager.flush();
      it.entityManager.clear();
      assertThat(it.deployTokenRepository.findById(targetBefore.id())).isEmpty();
      assertThat(
              it.deployTokenRepository.findByRepoIdAndToken(
                  repo.getStorageKey(), targetBefore.token()))
          .isEmpty();
      assertThat(it.stateOf(bystanderBefore.id())).isEqualTo(bystanderBefore);
    }

    @Test
    @DisplayName("returns 404 tokenNotFound when revoking the same token twice")
    void revokingTwice() throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(RepoType.MAVEN);
      final var target = it.seedToken(repo, "twice");
      final var token = it.adminBearerToken();
      expectSuccess(
          it.perform(delete(tokenUrl(repo, target.getId())).header(AUTHORIZATION, token)),
          "tokenRevoked");
      it.entityManager.flush();

      expectTokenNotFound(
          it.perform(delete(tokenUrl(repo, target.getId())).header(AUTHORIZATION, token)));
    }

    @Test
    @DisplayName("returns 404 tokenNotFound for an unknown token id")
    void unknownToken() throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(RepoType.MAVEN);

      expectTokenNotFound(
          it.perform(
              delete(tokenUrl(repo, UUID.randomUUID()))
                  .header(AUTHORIZATION, it.adminBearerToken())));
    }

    @Test
    @DisplayName("returns 404 tokenNotFound for a token of another repo and does not delete it")
    void tokenOfAnotherRepo() throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(RepoType.MAVEN);
      final var other = it.createRepo(RepoType.NPM);
      final var foreign = it.seedToken(other, "foreign");
      final var foreignBefore = it.stateOf(foreign.getId());

      expectTokenNotFound(
          it.perform(
              delete(tokenUrl(repo, foreignBefore.id()))
                  .header(AUTHORIZATION, it.adminBearerToken())));

      assertThat(it.stateOf(foreignBefore.id())).isEqualTo(foreignBefore);
    }

    @Test
    @DisplayName("returns 400 validationError naming tokenId when the id is not a UUID")
    void malformedTokenId() throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;
      final var repo = it.createRepo(RepoType.MAVEN);

      expectValidationError(
          it.perform(
              delete(tokenUrl(repo, "not-a-uuid")).header(AUTHORIZATION, it.adminBearerToken())),
          "tokenId");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Routing
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("routing")
  class Routing {

    /** Pins the RPS-849 behavior: unmapped verbs and routes answer 404 itemNotFound. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedMethods")
    @DisplayName("answers 404 itemNotFound for a route or verb nothing maps")
    void unsupportedMethod(final String label, final MockHttpServletRequestBuilder request)
        throws Exception {
      final var it = ProtocolDeployTokenControllerIT.this;

      expectError(
          it.perform(request.header(AUTHORIZATION, it.adminBearerToken())),
          HttpStatus.NOT_FOUND,
          "itemNotFound",
          null,
          "The requested item is not found.");
    }

    static Stream<Arguments> unsupportedMethods() {
      final var id = UUID.randomUUID();
      return Stream.of(
          Arguments.of("GET .../deploy-tokens/{id}", get("/api/repos/any/deploy-tokens/" + id)),
          Arguments.of("POST .../deploy-tokens/{id}", post("/api/repos/any/deploy-tokens/" + id)),
          Arguments.of("PATCH .../deploy-tokens/{id}", patch("/api/repos/any/deploy-tokens/" + id)),
          Arguments.of("DELETE .../deploy-tokens", delete("/api/repos/any/deploy-tokens")),
          Arguments.of("PUT .../deploy-tokens", put("/api/repos/any/deploy-tokens")));
    }
  }
}
