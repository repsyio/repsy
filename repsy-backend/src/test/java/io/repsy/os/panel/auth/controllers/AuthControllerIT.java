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
package io.repsy.os.panel.auth.controllers;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.jayway.jsonpath.JsonPath;
import io.repsy.os.RepsyApplication;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.PasswordGeneratorUtil;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.repositories.UserRepository;
import io.repsy.os.shared.user.services.UserTxService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAmount;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full-stack integration tests for {@code /api/auth/*}, exercising the real Spring context, MVC
 * dispatch and a containerized PostgreSQL database (Flyway-migrated) end to end.
 *
 * <p>Follows the pattern established by {@code ProfileControllerIT} and {@code UserControllerIT}:
 * requests go through {@link MockMvc}, but {@code AuthController} is only registered on the "api"
 * multiport connector, so every request is routed through {@link #apiPort()} to fake the local port
 * onto {@code multiport.ports.api} (8080).
 *
 * <p>Neither endpoint sits behind an auth interceptor (the only interceptor covers the {@code
 * /api/repos/**} and package-format paths), so no request here carries an {@code Authorization}
 * header unless the test needs one for a follow-up call.
 *
 * <p>Every test method runs in one transaction that is rolled back afterwards, so the only data
 * that survives between tests is what the application seeds at startup: the {@code admin} user,
 * whose password is pinned through {@code admin.initial-password}. The one exception is {@code
 * updatesLastLoginAt}: {@code UserLoginListener} is {@code @Async} and runs on another thread,
 * which cannot see rows that are still uncommitted inside a test transaction, so that test runs
 * without one and cleans up after itself. In every other test the listener still fires but finds no
 * such user and logs the failure on its own thread; that is expected noise, not a test failure.
 */
@Testcontainers
@AutoConfigureMockMvc
@Transactional
@SpringBootTest(
    classes = RepsyApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("AuthController /api/auth/*")
class AuthControllerIT {

  private static final int API_PORT = 8080;
  private static final String VALID_PASSWORD = "Password1!";
  private static final String OTHER_VALID_PASSWORD = "NewPassword2@";
  private static final String SEEDED_ADMIN_USERNAME = "admin";
  private static final String SEEDED_ADMIN_PASSWORD = "SeededAdmin1!";
  private static final String UUID_PATTERN =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
  private static final String VALIDATION_TEXT = "Incoming data couldn't be validated.";
  private static final String USER_NOT_FOUND_TEXT = "User not found.";
  private static final String WRONG_PASSWORD_TEXT =
      "Password is wrong, try another one or reset your password.";
  private static final String ACCESS_NOT_ALLOWED_TEXT = "Access isn't allowed.";
  private static final String INTERNAL_ERROR_TEXT = "An error occurred.";

  /**
   * The success ids that have an entry in messages.properties; every other success id renders its
   * msgId as the text.
   */
  private static final Map<String, String> SUCCESS_TEXTS =
      Map.of(
          "loginSucceeded", "Log In succeeded.",
          "passwordChanged", "Password changed.",
          "usernameUpdated", "Username successfully updated.",
          "profileDeleted", "Profile account deleted.");

  private static final String[] ENVELOPE_KEYS = {"msgId", "type", "data", "errorCode", "text"};
  private static final String[] LOGIN_INFO_KEYS = {"username", "token", "refreshToken"};

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18")
          .withDatabaseName("repsy")
          .withUsername("repsy")
          .withPassword("repsy123");

  @DynamicPropertySource
  static void registerDynamicProperties(final DynamicPropertyRegistry registry) {
    registry.add("storage-gateway.fs.base-path", AuthControllerIT::tempStoragePath);
    // Without this the seeded admin gets a random password that is only ever logged.
    registry.add("admin.initial-password", () -> SEEDED_ADMIN_PASSWORD);
  }

  private static String tempStoragePath() {
    try {
      return Files.createTempDirectory("repsy-auth-it").toString();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtils jwtUtils;
  @Autowired private UserTxService userTxService;
  @Autowired private UserRepository userRepository;
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

  private static String uniqueUsername(final String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
  }

  private static String loginBody(final String username, final String password) {
    return "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password);
  }

  private static String refreshBody(final String refreshToken) {
    return "{\"refreshToken\":\"%s\"}".formatted(refreshToken);
  }

  private ResultActions perform(final MockHttpServletRequestBuilder request) throws Exception {
    return this.mockMvc.perform(request.with(apiPort()));
  }

  private ResultActions login(final String body) throws Exception {
    return this.perform(
        post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private ResultActions login(final String username, final String password) throws Exception {
    return this.login(loginBody(username, password));
  }

  private ResultActions refresh(final String body) throws Exception {
    return this.perform(
        post("/api/auth/tokens/refresh").contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private ResultActions refreshWith(final String refreshToken) throws Exception {
    return this.refresh(refreshBody(refreshToken));
  }

  /**
   * Creates a user with {@link #VALID_PASSWORD} and flushes it, so {@code createdAt} is populated
   * and the row is visible to the queries the controller runs.
   */
  private User createUser(final String username, final UserRole role) {
    final var salt = PasswordGeneratorUtil.generateSalt();
    final var hash = PasswordGeneratorUtil.hashPassword(VALID_PASSWORD, salt);
    final var userInfo = this.userTxService.create(username, role, hash, salt);
    this.entityManager.flush();
    return this.userRepository.findById(userInfo.getId()).orElseThrow();
  }

  private User seededAdmin() {
    return this.userRepository.findByUsername(SEEDED_ADMIN_USERNAME).orElseThrow();
  }

  private String bearerTokenFor(final User user) {
    return AuthUtils.AUTH_BEARER
        + this.jwtUtils.createTokenWithDuration(
            user.getId(), user.getUsername(), Duration.ofMinutes(30));
  }

  private String adminBearerToken() {
    return this.bearerTokenFor(this.createUser(uniqueUsername("caller"), UserRole.ADMIN));
  }

  /** The signing secret of the running application (random per context unless configured). */
  private String serverSecret() {
    return (String) ReflectionTestUtils.getField(this.jwtUtils, "secret");
  }

  private static String signedToken(
      final String subject, final String username, final Instant expiresAt, final Algorithm alg) {
    var builder = JWT.create().withClaim("username", username).withExpiresAt(expiresAt);
    if (subject != null) {
      builder = builder.withSubject(subject);
    }
    return builder.sign(alg);
  }

  private static String signedRefreshToken(
      final String subject, final String username, final Instant expiresAt, final Algorithm alg) {
    var builder =
        JWT.create()
            .withClaim("username", username)
            .withClaim("token_type", "refresh")
            .withExpiresAt(expiresAt);
    if (subject != null) {
      builder = builder.withSubject(subject);
    }
    return builder.sign(alg);
  }

  /** Rewrites the subject inside a valid token's payload without touching its signature. */
  private static String withSubject(
      final String token, final UUID originalSubject, final UUID newSubject) {
    final var parts = token.split("\\.");
    final var payload =
        new String(Base64.getUrlDecoder().decode(parts[1]), UTF_8)
            .replace(originalSubject.toString(), newSubject.toString());
    return parts[0]
        + "."
        + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(UTF_8))
        + "."
        + parts[2];
  }

  // ---------------------------------------------------------------------------------------------
  // Response helpers
  // ---------------------------------------------------------------------------------------------

  /**
   * Asserts a 200 SUCCESS envelope (exact key set, {@code errorCode} null, {@code text} taken from
   * {@link #SUCCESS_TEXTS} or else falling back to the msgId) and returns the raw body for further
   * assertions on {@code data}.
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
        .containsEntry("text", SUCCESS_TEXTS.getOrDefault(msgId, msgId));
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

  private static void expectValidationError(final ResultActions result) throws Exception {
    expectError(result, HttpStatus.BAD_REQUEST, "validationError", null, VALIDATION_TEXT);
  }

  private static void expectUserNotFound(final ResultActions result) throws Exception {
    expectError(result, HttpStatus.NOT_FOUND, "userNotFound", "userNotFound", USER_NOT_FOUND_TEXT);
  }

  private static void expectAccessNotAllowed(final ResultActions result) throws Exception {
    expectError(
        result,
        HttpStatus.FORBIDDEN,
        "accessNotAllowed",
        "accessNotAllowed",
        ACCESS_NOT_ALLOWED_TEXT);
  }

  private static void expectInternalError(final ResultActions result) throws Exception {
    expectError(
        result, HttpStatus.INTERNAL_SERVER_ERROR, "errorOccurred", null, INTERNAL_ERROR_TEXT);
  }

  /**
   * Asserts the complete {@code LoginInfo} shape for {@code expectedUser} and that both JWTs verify
   * against the application's secret, carry the user's id and username, and expire exactly one
   * access-token / refresh-token lifetime after issuance (JWT {@code exp} has second precision).
   */
  private void assertLoginInfo(
      final String body,
      final UUID expectedUserId,
      final String expectedUsername,
      final Instant issuedNoEarlierThan,
      final Instant issuedNoLaterThan) {
    final Map<String, Object> data = JsonPath.read(body, "$.data");
    assertThat(data).containsOnlyKeys(LOGIN_INFO_KEYS).containsEntry("username", expectedUsername);

    final var accessToken = (String) data.get("token");
    final var refreshToken = (String) data.get("refreshToken");
    assertThat(accessToken).isNotBlank().isNotEqualTo(refreshToken);
    assertThat(refreshToken).isNotBlank();

    // The access token works on the access side and carries no token type.
    final var accessTokenDecoded = JWT.decode(accessToken);
    assertThat(this.jwtUtils.getUserId(accessToken)).isEqualTo(expectedUserId);
    assertThat(this.jwtUtils.verifyAndExtractUsername(AuthUtils.AUTH_BEARER + accessToken))
        .isEqualTo(expectedUsername);
    assertThat(accessTokenDecoded.getClaim("token_type").asString()).isNull();

    // The refresh token is only valid as a refresh token.
    final var refreshTokenDecoded = JWT.decode(refreshToken);
    assertThat(this.jwtUtils.verifyRefreshToken(refreshToken)).isEqualTo(expectedUserId);
    assertThat(refreshTokenDecoded.getClaim("username").asString()).isEqualTo(expectedUsername);
    assertThat(refreshTokenDecoded.getClaim("token_type").asString()).isEqualTo("refresh");

    assertExpiry(
        accessTokenDecoded, AuthUtils.TIMEOUT_ACCESS_TOKEN, issuedNoEarlierThan, issuedNoLaterThan);
    assertExpiry(
        refreshTokenDecoded,
        AuthUtils.TIMEOUT_REFRESH_TOKEN,
        issuedNoEarlierThan,
        issuedNoLaterThan);
  }

  private static void assertExpiry(
      final DecodedJWT jwt,
      final TemporalAmount lifetime,
      final Instant issuedNoEarlierThan,
      final Instant issuedNoLaterThan) {
    assertThat(jwt.getExpiresAtAsInstant())
        .isBetween(
            issuedNoEarlierThan.plus(lifetime).truncatedTo(ChronoUnit.SECONDS),
            issuedNoLaterThan.plus(lifetime));
  }

  // ---------------------------------------------------------------------------------------------
  // POST /api/auth/login
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("POST /api/auth/login")
  class Login {

    @Test
    @DisplayName("returns loginSucceeded with the full LoginInfo and correctly dated JWTs")
    void logsIn() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("login"), UserRole.USER);

      final var before = Instant.now();
      final var body =
          expectSuccess(
              AuthControllerIT.this.login(user.getUsername(), VALID_PASSWORD), "loginSucceeded");
      final var after = Instant.now();

      AuthControllerIT.this.assertLoginInfo(body, user.getId(), user.getUsername(), before, after);
    }

    @Test
    @DisplayName("logs in the seeded admin with the configured initial password")
    void logsInSeededAdmin() throws Exception {
      final var admin = AuthControllerIT.this.seededAdmin();

      final var before = Instant.now();
      final var body =
          expectSuccess(
              AuthControllerIT.this.login(SEEDED_ADMIN_USERNAME, SEEDED_ADMIN_PASSWORD),
              "loginSucceeded");
      final var after = Instant.now();

      AuthControllerIT.this.assertLoginInfo(
          body, admin.getId(), SEEDED_ADMIN_USERNAME, before, after);
    }

    @Test
    @DisplayName("needs no Authorization header, and ignores an invalid one")
    void ignoresAuthorizationHeader() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("noauth"), UserRole.USER);

      expectSuccess(
          AuthControllerIT.this.perform(
              post("/api/auth/login")
                  .header(AUTHORIZATION, "Bearer not-a-jwt")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(loginBody(user.getUsername(), VALID_PASSWORD))),
          "loginSucceeded");
    }

    @Test
    @DisplayName("issues an access token that an authenticated endpoint accepts")
    void accessTokenWorksOnAuthenticatedEndpoint() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("profile"), UserRole.USER);
      final var loginBody =
          expectSuccess(
              AuthControllerIT.this.login(user.getUsername(), VALID_PASSWORD), "loginSucceeded");
      final String accessToken = JsonPath.read(loginBody, "$.data.token");

      final var profileBody =
          expectSuccess(
              AuthControllerIT.this.perform(
                  get("/api/profile").header(AUTHORIZATION, AuthUtils.AUTH_BEARER + accessToken)),
              "profileFetched");

      assertThat((String) JsonPath.read(profileBody, "$.data.id"))
          .isEqualTo(user.getId().toString());
      assertThat((String) JsonPath.read(profileBody, "$.data.username"))
          .isEqualTo(user.getUsername());
    }

    @Test
    @DisplayName("updates lastLoginAt through the async UserLoginEvent listener, only on success")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void updatesLastLoginAt() throws Exception {
      // Committed for real: the async listener runs on another thread and must be able to see it.
      final var salt = PasswordGeneratorUtil.generateSalt();
      final var hash = PasswordGeneratorUtil.hashPassword(VALID_PASSWORD, salt);
      final var userId =
          AuthControllerIT.this
              .userTxService
              .create(uniqueUsername("lastlogin"), UserRole.USER, hash, salt)
              .getId();
      final var username =
          AuthControllerIT.this.userRepository.findById(userId).orElseThrow().getUsername();

      try {
        assertThat(AuthControllerIT.this.userRepository.findById(userId).orElseThrow())
            .extracting(User::getLastLoginAt)
            .isNull();

        // A failed attempt publishes no event, so lastLoginAt stays empty.
        expectError(
            AuthControllerIT.this.login(username, OTHER_VALID_PASSWORD),
            HttpStatus.FORBIDDEN,
            "wrongPassword",
            "wrongPassword",
            WRONG_PASSWORD_TEXT);
        assertThat(AuthControllerIT.this.userRepository.findById(userId).orElseThrow())
            .extracting(User::getLastLoginAt)
            .isNull();

        final var before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        expectSuccess(AuthControllerIT.this.login(username, VALID_PASSWORD), "loginSucceeded");

        await()
            .atMost(10, TimeUnit.SECONDS)
            .untilAsserted(
                () ->
                    assertThat(
                            AuthControllerIT.this
                                .userRepository
                                .findById(userId)
                                .orElseThrow()
                                .getLastLoginAt())
                        .isNotNull()
                        .isBetween(before, Instant.now()));
      } finally {
        AuthControllerIT.this.userRepository.deleteById(userId);
      }
    }

    @Test
    @DisplayName("returns 404 userNotFound for an unknown username")
    void unknownUsername() throws Exception {
      expectUserNotFound(AuthControllerIT.this.login(uniqueUsername("ghost"), VALID_PASSWORD));
    }

    @Test
    @DisplayName("returns 403 wrongPassword for a wrong (but well-formed) password")
    void wrongPassword() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("wrongpw"), UserRole.USER);

      expectError(
          AuthControllerIT.this.login(user.getUsername(), OTHER_VALID_PASSWORD),
          HttpStatus.FORBIDDEN,
          "wrongPassword",
          "wrongPassword",
          WRONG_PASSWORD_TEXT);
    }

    @Test
    @DisplayName("returns 403 wrongPassword for the seeded admin, so the admin row is resolvable")
    void wrongPasswordForSeededAdmin() throws Exception {
      expectError(
          AuthControllerIT.this.login(SEEDED_ADMIN_USERNAME, VALID_PASSWORD),
          HttpStatus.FORBIDDEN,
          "wrongPassword",
          "wrongPassword",
          WRONG_PASSWORD_TEXT);
    }

    @Test
    @DisplayName("looks the username up case-sensitively: another casing is 404 userNotFound")
    void usernameLookupIsCaseSensitive() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("MixedCase"), UserRole.USER);
      assertThat(user.getUsername()).isNotEqualTo(user.getUsername().toLowerCase());

      expectUserNotFound(
          AuthControllerIT.this.login(user.getUsername().toLowerCase(), VALID_PASSWORD));
      expectUserNotFound(
          AuthControllerIT.this.login(user.getUsername().toUpperCase(), VALID_PASSWORD));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("usernamesWithWhitespace")
    @DisplayName("rejects surrounding or embedded whitespace in the username with a 400, no trim")
    void usernameWhitespace(final String name, final String username) throws Exception {
      expectValidationError(AuthControllerIT.this.login(username, VALID_PASSWORD));
    }

    static Stream<Arguments> usernamesWithWhitespace() {
      // The values are spliced into a JSON string, so "\\t" and "\\n" reach the server as escapes.
      return Stream.of(
          Arguments.of("leading space", " someuser"),
          Arguments.of("trailing space", "someuser "),
          Arguments.of("embedded space", "some user"),
          Arguments.of("trailing tab", "someuser\\t"),
          Arguments.of("trailing newline", "someuser\\n"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    @DisplayName("returns 400 validationError for an invalid LoginForm")
    void invalidBody(final String name, final String body) throws Exception {
      expectValidationError(AuthControllerIT.this.login(body));
    }

    static Stream<Arguments> invalidBodies() {
      return Stream.of(
          Arguments.of("missing username", "{\"password\":\"Password1!\"}"),
          Arguments.of("missing password", "{\"username\":\"someuser\"}"),
          Arguments.of("empty object", "{}"),
          Arguments.of("null username", "{\"username\":null,\"password\":\"Password1!\"}"),
          Arguments.of("null password", "{\"username\":\"someuser\",\"password\":null}"),
          Arguments.of("empty username", loginBody("", VALID_PASSWORD)),
          Arguments.of("blank username", loginBody("   ", VALID_PASSWORD)),
          Arguments.of("empty password", loginBody("someuser", "")),
          Arguments.of("blank password", loginBody("someuser", "      ")),
          Arguments.of("username too short", loginBody("ab", VALID_PASSWORD)),
          Arguments.of("username too long", loginBody("a".repeat(151), VALID_PASSWORD)),
          Arguments.of("username with illegal characters", loginBody("bad!name", VALID_PASSWORD)),
          Arguments.of("password too short", loginBody("someuser", "Ab1de")),
          Arguments.of("password too long", loginBody("someuser", "Aa1" + "x".repeat(48))),
          Arguments.of("password without uppercase", loginBody("someuser", "lowercase1")),
          Arguments.of("password without lowercase", loginBody("someuser", "UPPERCASE1")),
          Arguments.of("password without digit", loginBody("someuser", "NoDigitsHere")),
          Arguments.of("password with whitespace", loginBody("someuser", "Has Space1")),
          Arguments.of("null body", "null"),
          Arguments.of("empty body", ""),
          Arguments.of("malformed JSON", "{not-json"),
          Arguments.of("JSON array", "[]"),
          Arguments.of("JSON string", "\"someuser\""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("boundaryUsernames")
    @DisplayName("accepts usernames at the form's length limits (then 404 userNotFound)")
    void boundaryUsernames(final String name, final String username) throws Exception {
      expectUserNotFound(AuthControllerIT.this.login(username, VALID_PASSWORD));
    }

    static Stream<Arguments> boundaryUsernames() {
      return Stream.of(
          Arguments.of("3-char username", "abc"),
          Arguments.of("150-char username", "a".repeat(150)));
    }

    @Test
    @DisplayName("returns 400 validationError for an unsupported content type")
    void unsupportedContentType() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("plain"), UserRole.USER);

      expectValidationError(
          AuthControllerIT.this.perform(
              post("/api/auth/login")
                  .contentType(MediaType.TEXT_PLAIN)
                  .content(loginBody(user.getUsername(), VALID_PASSWORD))));
    }

    @Test
    @DisplayName("returns 400 validationError when no content type is sent")
    void missingContentType() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("notype"), UserRole.USER);

      expectValidationError(
          AuthControllerIT.this.perform(
              post("/api/auth/login").content(loginBody(user.getUsername(), VALID_PASSWORD))));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Login across the credential lifecycle
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("login across the credential lifecycle")
  class CredentialLifecycle {

    @Test
    @DisplayName("a user created through POST /api/users can log in with the password it was given")
    void createdUserCanLogIn() throws Exception {
      final var adminToken = AuthControllerIT.this.adminBearerToken();
      final var username = uniqueUsername("created");

      expectSuccess(
          AuthControllerIT.this.perform(
              post("/api/users")
                  .header(AUTHORIZATION, adminToken)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"username\":\"%s\",\"password\":\"%s\",\"role\":\"USER\"}"
                          .formatted(username, OTHER_VALID_PASSWORD))),
          "userCreated");
      AuthControllerIT.this.entityManager.flush();
      final var created =
          AuthControllerIT.this.userRepository.findByUsername(username).orElseThrow();

      final var before = Instant.now();
      final var body =
          expectSuccess(
              AuthControllerIT.this.login(username, OTHER_VALID_PASSWORD), "loginSucceeded");
      AuthControllerIT.this.assertLoginInfo(body, created.getId(), username, before, Instant.now());

      expectError(
          AuthControllerIT.this.login(username, VALID_PASSWORD),
          HttpStatus.FORBIDDEN,
          "wrongPassword",
          "wrongPassword",
          WRONG_PASSWORD_TEXT);
    }

    @Test
    @DisplayName("after PUT /api/profile/password only the new password logs in")
    void passwordChangeReplacesTheOldPassword() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("changepw"), UserRole.USER);
      expectSuccess(
          AuthControllerIT.this.login(user.getUsername(), VALID_PASSWORD), "loginSucceeded");

      expectSuccess(
          AuthControllerIT.this.perform(
              put("/api/profile/password")
                  .header(AUTHORIZATION, AuthControllerIT.this.bearerTokenFor(user))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"password\":\"%s\"}".formatted(OTHER_VALID_PASSWORD))),
          "passwordChanged");
      AuthControllerIT.this.entityManager.flush();

      expectError(
          AuthControllerIT.this.login(user.getUsername(), VALID_PASSWORD),
          HttpStatus.FORBIDDEN,
          "wrongPassword",
          "wrongPassword",
          WRONG_PASSWORD_TEXT);
      final var before = Instant.now();
      final var body =
          expectSuccess(
              AuthControllerIT.this.login(user.getUsername(), OTHER_VALID_PASSWORD),
              "loginSucceeded");
      AuthControllerIT.this.assertLoginInfo(
          body, user.getId(), user.getUsername(), before, Instant.now());
    }

    @Test
    @DisplayName("after PUT /api/profile/username the old username is unknown, the new one works")
    void usernameChangeMovesTheLogin() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("rename"), UserRole.USER);
      final var oldUsername = user.getUsername();
      final var newUsername = uniqueUsername("renamed");
      expectSuccess(
          AuthControllerIT.this.perform(
              put("/api/profile/username")
                  .header(AUTHORIZATION, AuthControllerIT.this.bearerTokenFor(user))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"username\":\"%s\"}".formatted(newUsername))),
          "usernameUpdated");
      AuthControllerIT.this.entityManager.flush();

      expectUserNotFound(AuthControllerIT.this.login(oldUsername, VALID_PASSWORD));
      final var before = Instant.now();
      final var body =
          expectSuccess(AuthControllerIT.this.login(newUsername, VALID_PASSWORD), "loginSucceeded");
      AuthControllerIT.this.assertLoginInfo(body, user.getId(), newUsername, before, Instant.now());
    }

    @Test
    @DisplayName("a user deleted through DELETE /api/users/{id} can no longer log in")
    void deletedByAdminCannotLogIn() throws Exception {
      final var adminToken = AuthControllerIT.this.adminBearerToken();
      final var user = AuthControllerIT.this.createUser(uniqueUsername("deladmin"), UserRole.USER);
      expectSuccess(
          AuthControllerIT.this.login(user.getUsername(), VALID_PASSWORD), "loginSucceeded");

      expectSuccess(
          AuthControllerIT.this.perform(
              delete("/api/users/" + user.getId()).header(AUTHORIZATION, adminToken)),
          "userDeleted");
      AuthControllerIT.this.entityManager.flush();

      expectUserNotFound(AuthControllerIT.this.login(user.getUsername(), VALID_PASSWORD));
    }

    @Test
    @DisplayName("a user who deleted their own profile can no longer log in")
    void deletedOwnProfileCannotLogIn() throws Exception {
      // The seeded admin keeps the "last admin" guard out of the way; this is a plain USER anyway.
      final var user = AuthControllerIT.this.createUser(uniqueUsername("delself"), UserRole.USER);
      expectSuccess(
          AuthControllerIT.this.perform(
              delete("/api/profile")
                  .header(AUTHORIZATION, AuthControllerIT.this.bearerTokenFor(user))),
          "profileDeleted");
      AuthControllerIT.this.entityManager.flush();

      expectUserNotFound(AuthControllerIT.this.login(user.getUsername(), VALID_PASSWORD));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // POST /api/auth/tokens/refresh
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("POST /api/auth/tokens/refresh")
  class RefreshToken {

    @Test
    @DisplayName("exchanges the refresh token from a login for a new, full LoginInfo")
    void refreshesTokens() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("refresh"), UserRole.USER);
      final var loginBody =
          expectSuccess(
              AuthControllerIT.this.login(user.getUsername(), VALID_PASSWORD), "loginSucceeded");
      final String refreshToken = JsonPath.read(loginBody, "$.data.refreshToken");

      final var before = Instant.now();
      final var body =
          expectSuccess(AuthControllerIT.this.refreshWith(refreshToken), "tokenRefreshed");
      final var after = Instant.now();

      AuthControllerIT.this.assertLoginInfo(body, user.getId(), user.getUsername(), before, after);
      // The refreshed tokens work on an authenticated endpoint.
      final String newAccessToken = JsonPath.read(body, "$.data.token");
      expectSuccess(
          AuthControllerIT.this.perform(
              get("/api/profile").header(AUTHORIZATION, AuthUtils.AUTH_BEARER + newAccessToken)),
          "profileFetched");
    }

    @Test
    @DisplayName("needs no Authorization header, and ignores an invalid one")
    void ignoresAuthorizationHeader() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("noauth"), UserRole.USER);
      final var refreshToken =
          AuthControllerIT.this.jwtUtils.createRefreshToken(
              user.getId(), user.getUsername(), AuthUtils.TIMEOUT_REFRESH_TOKEN);

      expectSuccess(
          AuthControllerIT.this.perform(
              post("/api/auth/tokens/refresh")
                  .header(AUTHORIZATION, "Bearer not-a-jwt")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(refreshBody(refreshToken))),
          "tokenRefreshed");
    }

    @Test
    @DisplayName(
        "reflects the user's current username, since the user is resolved by token subject")
    void usesCurrentUsername() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("before"), UserRole.USER);
      final var refreshToken =
          AuthControllerIT.this.jwtUtils.createRefreshToken(
              user.getId(), user.getUsername(), AuthUtils.TIMEOUT_REFRESH_TOKEN);
      final var newUsername = uniqueUsername("after");
      AuthControllerIT.this.userTxService.updateUsername(user.getId(), newUsername);
      AuthControllerIT.this.entityManager.flush();

      final var before = Instant.now();
      final var body =
          expectSuccess(AuthControllerIT.this.refreshWith(refreshToken), "tokenRefreshed");

      AuthControllerIT.this.assertLoginInfo(body, user.getId(), newUsername, before, Instant.now());
    }

    @Test
    @DisplayName("returns 403 accessNotAllowed for an access token")
    void rejectsAnAccessTokenAsRefreshToken() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("access"), UserRole.USER);
      final var loginBody =
          expectSuccess(
              AuthControllerIT.this.login(user.getUsername(), VALID_PASSWORD), "loginSucceeded");
      final String accessToken = JsonPath.read(loginBody, "$.data.token");

      expectAccessNotAllowed(AuthControllerIT.this.refreshWith(accessToken));
    }

    @Test
    @DisplayName("returns 403 accessNotAllowed for a claim-less legacy token from the 3-arg method")
    void rejectsAClaimlessLegacyToken() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("legacy"), UserRole.USER);
      final var legacyToken =
          AuthControllerIT.this.jwtUtils.createTokenWithDuration(
              user.getId(), user.getUsername(), AuthUtils.TIMEOUT_REFRESH_TOKEN);

      expectAccessNotAllowed(AuthControllerIT.this.refreshWith(legacyToken));
    }

    @Test
    @DisplayName("returns 403 refreshTokenExpired for an expired token")
    void expiredToken() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("expired"), UserRole.USER);
      final var expired =
          AuthControllerIT.this.jwtUtils.createRefreshToken(
              user.getId(), user.getUsername(), Duration.ofSeconds(-30));

      expectError(
          AuthControllerIT.this.refreshWith(expired),
          HttpStatus.FORBIDDEN,
          "refreshTokenExpired",
          "refreshTokenExpired",
          "refreshTokenExpired");
    }

    @Test
    @DisplayName("returns 403 accessNotAllowed for a token that is not a JWT")
    void malformedToken() throws Exception {
      expectAccessNotAllowed(AuthControllerIT.this.refreshWith("not-a-jwt"));
    }

    @Test
    @DisplayName("returns 403 accessNotAllowed for an empty token")
    void emptyToken() throws Exception {
      expectAccessNotAllowed(AuthControllerIT.this.refreshWith(""));
    }

    @Test
    @DisplayName("returns 403 accessNotAllowed for a token whose payload was tampered with")
    void tamperedToken() throws Exception {
      final var victim = AuthControllerIT.this.createUser(uniqueUsername("victim"), UserRole.USER);
      final var attacker =
          AuthControllerIT.this.createUser(uniqueUsername("attack"), UserRole.USER);
      final var attackerToken =
          AuthControllerIT.this.jwtUtils.createRefreshToken(
              attacker.getId(), attacker.getUsername(), AuthUtils.TIMEOUT_REFRESH_TOKEN);
      final var forged = withSubject(attackerToken, attacker.getId(), victim.getId());
      assertThat(forged).isNotEqualTo(attackerToken);

      expectAccessNotAllowed(AuthControllerIT.this.refreshWith(forged));
    }

    @Test
    @DisplayName("returns 403 accessNotAllowed for a token signed with a different secret")
    void wronglySignedToken() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("wrongkey"), UserRole.USER);
      final var token =
          signedToken(
              user.getId().toString(),
              user.getUsername(),
              Instant.now().plus(AuthUtils.TIMEOUT_REFRESH_TOKEN),
              Algorithm.HMAC512("not-the-server-secret"));

      expectAccessNotAllowed(AuthControllerIT.this.refreshWith(token));
    }

    @Test
    @DisplayName("returns 403 accessNotAllowed for an unsigned (alg=none) token")
    void unsignedToken() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("nonealg"), UserRole.USER);
      final var token =
          signedToken(
              user.getId().toString(),
              user.getUsername(),
              Instant.now().plus(AuthUtils.TIMEOUT_REFRESH_TOKEN),
              Algorithm.none());

      expectAccessNotAllowed(AuthControllerIT.this.refreshWith(token));
    }

    @Test
    @DisplayName("returns 404 userNotFound when the token's user does not exist")
    void unknownUser() throws Exception {
      final var token =
          AuthControllerIT.this.jwtUtils.createRefreshToken(
              UUID.randomUUID(), uniqueUsername("ghost"), AuthUtils.TIMEOUT_REFRESH_TOKEN);

      expectUserNotFound(AuthControllerIT.this.refreshWith(token));
    }

    @Test
    @DisplayName("returns 404 userNotFound once the token's user has been deleted")
    void deletedUser() throws Exception {
      final var adminToken = AuthControllerIT.this.adminBearerToken();
      final var user = AuthControllerIT.this.createUser(uniqueUsername("deleted"), UserRole.USER);
      final var loginBody =
          expectSuccess(
              AuthControllerIT.this.login(user.getUsername(), VALID_PASSWORD), "loginSucceeded");
      final String refreshToken = JsonPath.read(loginBody, "$.data.refreshToken");
      expectSuccess(
          AuthControllerIT.this.perform(
              delete("/api/users/" + user.getId()).header(AUTHORIZATION, adminToken)),
          "userDeleted");
      AuthControllerIT.this.entityManager.flush();

      expectUserNotFound(AuthControllerIT.this.refreshWith(refreshToken));
    }

    @Test
    @DisplayName(
        "currently answers 500 errorOccurred for a validly signed token whose subject is no UUID")
    void nonUuidSubject() throws Exception {
      final var token =
          signedRefreshToken(
              "not-a-uuid",
              "someuser",
              Instant.now().plus(AuthUtils.TIMEOUT_REFRESH_TOKEN),
              Algorithm.HMAC512(AuthControllerIT.this.serverSecret()));

      expectInternalError(AuthControllerIT.this.refreshWith(token));
    }

    @Test
    @DisplayName("currently answers 500 errorOccurred for a validly signed token without a subject")
    void missingSubject() throws Exception {
      final var token =
          signedRefreshToken(
              null,
              "someuser",
              Instant.now().plus(AuthUtils.TIMEOUT_REFRESH_TOKEN),
              Algorithm.HMAC512(AuthControllerIT.this.serverSecret()));

      expectInternalError(AuthControllerIT.this.refreshWith(token));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    @DisplayName("returns 400 validationError for an unreadable RefreshTokenForm")
    void invalidBody(final String name, final String body) throws Exception {
      expectValidationError(AuthControllerIT.this.refresh(body));
    }

    static Stream<Arguments> invalidBodies() {
      return Stream.of(
          Arguments.of("null body", "null"),
          Arguments.of("empty body", ""),
          Arguments.of("malformed JSON", "{not-json"),
          Arguments.of("JSON array", "[]"),
          Arguments.of("JSON string", "\"token\""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bodiesWithoutToken")
    @DisplayName(
        "answers 403 accessNotAllowed when the form carries no refreshToken (no @NotNull on it)")
    void missingRefreshToken(final String name, final String body) throws Exception {
      expectAccessNotAllowed(AuthControllerIT.this.refresh(body));
    }

    static Stream<Arguments> bodiesWithoutToken() {
      return Stream.of(
          Arguments.of("empty object", "{}"),
          Arguments.of("null refreshToken", "{\"refreshToken\":null}"));
    }

    @Test
    @DisplayName("returns 403 accessNotAllowed when used as Bearer on an authenticated endpoint")
    void refreshTokenRejectedOnAuthenticatedEndpoint() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("refbearer"), UserRole.USER);
      final var loginBody =
          expectSuccess(
              AuthControllerIT.this.login(user.getUsername(), VALID_PASSWORD), "loginSucceeded");
      final String refreshToken = JsonPath.read(loginBody, "$.data.refreshToken");

      expectAccessNotAllowed(
          AuthControllerIT.this.perform(
              get("/api/profile").header(AUTHORIZATION, AuthUtils.AUTH_BEARER + refreshToken)));
    }

    @Test
    @DisplayName("returns 400 validationError for an unsupported content type")
    void unsupportedContentType() throws Exception {
      expectValidationError(
          AuthControllerIT.this.perform(
              post("/api/auth/tokens/refresh")
                  .contentType(MediaType.TEXT_PLAIN)
                  .content(refreshBody("some-token"))));
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
      expectError(
          AuthControllerIT.this.perform(request),
          HttpStatus.NOT_FOUND,
          "itemNotFound",
          null,
          "The requested item is not found.");
    }

    static Stream<Arguments> unsupportedMethods() {
      return Stream.of(
          Arguments.of("GET /api/auth/login", get("/api/auth/login")),
          Arguments.of("PUT /api/auth/login", put("/api/auth/login")),
          Arguments.of("DELETE /api/auth/login", delete("/api/auth/login")),
          Arguments.of("GET /api/auth/tokens/refresh", get("/api/auth/tokens/refresh")),
          Arguments.of("PATCH /api/auth/tokens/refresh", patch("/api/auth/tokens/refresh")),
          Arguments.of("PUT /api/auth/tokens/refresh", put("/api/auth/tokens/refresh")));
    }
  }
}
