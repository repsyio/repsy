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

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.jayway.jsonpath.JsonPath;
import io.repsy.core.events.UserLoginEvent;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.auth.utils.PasswordGeneratorUtil;
import io.repsy.os.shared.auth.utils.TokenRealm;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
 * whose password {@link #SEEDED_ADMIN_PASSWORD} is pinned through {@code admin.initial-password} in
 * {@link AbstractIntegrationTest}. The one exception is {@code updatesLastLoginAt}: {@code
 * UserLoginListener} is {@code @Async} and runs on another thread, which cannot see rows that are
 * still uncommitted inside a test transaction, so that test runs without one and cleans up after
 * itself. In every other test the listener still fires but finds no such user and logs the failure
 * on its own thread; that is expected noise, not a test failure.
 */
@RecordApplicationEvents
@DisplayName("AuthController /api/auth/*")
class AuthControllerIT extends AbstractIntegrationTest {

  private static final String OTHER_VALID_PASSWORD = "NewPassword2@";
  private static final String VALIDATION_TEXT = "Incoming data couldn't be validated.";
  private static final String UNSUPPORTED_MEDIA_TYPE_TEXT = "Unsupported media type.";
  private static final String INVALID_CREDENTIALS_TEXT = "Username or password is incorrect.";
  private static final String ACCESS_NOT_ALLOWED_TEXT = "Access isn't allowed.";
  private static final String INTERNAL_ERROR_TEXT = "An error occurred.";

  /** The text messages.properties gives each success id these tests assert on. */
  private static final Map<String, String> SUCCESS_TEXTS =
      Map.of(
          "loginSucceeded", "Log In succeeded.",
          "passwordChanged", "Password changed.",
          "passwordReset", "Password reset.",
          "usernameUpdated", "Username successfully updated.",
          "profileDeleted", "Profile account deleted.",
          "profileFetched", "Profile fetched.",
          "tokenRefreshed", "Token refreshed.",
          "userCreated", "User created.",
          "userUpdated", "User updated.",
          "userDeleted", "User deleted.");

  private static final String[] LOGIN_INFO_KEYS = {"username", "token", "refreshToken"};

  // ---------------------------------------------------------------------------------------------
  // Request / fixture helpers
  // ---------------------------------------------------------------------------------------------

  private static String loginBody(final String username, final String password) {
    return "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password);
  }

  private static String refreshBody(final String refreshToken) {
    return "{\"refreshToken\":\"%s\"}".formatted(refreshToken);
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

  /** A refresh token of a session the user logged into just now, at the user's current version. */
  private String refreshTokenFor(final User user) {
    return this.refreshTokenFor(user, Instant.now(), user.getTokenVersion());
  }

  private String refreshTokenFor(
      final User user, final Instant sessionStart, final int tokenVersion) {
    return this.jwtUtils.createRefreshToken(
        user.getId(),
        user.getUsername(),
        AuthUtils.TIMEOUT_REFRESH_TOKEN,
        sessionStart,
        tokenVersion);
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
            .withClaim("session_start", Instant.now())
            .withClaim("token_version", 0)
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
   * {@link #SUCCESS_TEXTS}) and returns the raw body for further assertions on {@code data}.
   */
  private static String expectSuccess(final ResultActions result, final String msgId)
      throws Exception {
    return expectSuccess(result, msgId, SUCCESS_TEXTS.get(msgId));
  }

  private static void expectValidationError(final ResultActions result) throws Exception {
    expectError(result, HttpStatus.BAD_REQUEST, "validationError", null, VALIDATION_TEXT);
  }

  private static void expectUnsupportedMediaType(final ResultActions result) throws Exception {
    expectError(
        result,
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "unsupportedMediaType",
        null,
        UNSUPPORTED_MEDIA_TYPE_TEXT);
  }

  private static void expectUnauthorized(final ResultActions result) throws Exception {
    expectError(
        result,
        HttpStatus.UNAUTHORIZED,
        "unAuthorized",
        "unAuthorized",
        "The user has logged in but has no permissions.");
  }

  private static void expectInvalidCredentials(final ResultActions result) throws Exception {
    expectError(
        result,
        HttpStatus.FORBIDDEN,
        "invalidCredentials",
        "invalidCredentials",
        INVALID_CREDENTIALS_TEXT);
  }

  private static void expectAccessNotAllowed(final ResultActions result) throws Exception {
    expectError(
        result,
        HttpStatus.UNAUTHORIZED,
        "accessNotAllowed",
        "accessNotAllowed",
        ACCESS_NOT_ALLOWED_TEXT);
  }

  private static void expectRefreshTokenExpired(final ResultActions result) throws Exception {
    expectError(
        result,
        HttpStatus.UNAUTHORIZED,
        "refreshTokenExpired",
        "refreshTokenExpired",
        "Refresh token expired.");
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
    assertThat(this.jwtUtils.getUserId(accessToken, TokenRealm.PANEL)).isEqualTo(expectedUserId);
    assertThat(
            this.jwtUtils.verifyAndExtractUsername(
                AuthUtils.AUTH_BEARER + accessToken, TokenRealm.PANEL))
        .isEqualTo(expectedUsername);
    assertThat(accessTokenDecoded.getClaim("token_type").asString()).isNull();
    assertThat(accessTokenDecoded.getAudience()).containsExactly("panel");

    // The refresh token is only valid as a refresh token.
    final var refreshTokenDecoded = JWT.decode(refreshToken);
    assertThat(this.jwtUtils.verifyRefreshToken(refreshToken).userId()).isEqualTo(expectedUserId);
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
            "invalidCredentials",
            "invalidCredentials",
            INVALID_CREDENTIALS_TEXT);
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
    @DisplayName("returns 403 invalidCredentials for an unknown username")
    void unknownUsername() throws Exception {
      expectInvalidCredentials(
          AuthControllerIT.this.login(uniqueUsername("ghost"), VALID_PASSWORD));
    }

    @Test
    @DisplayName("returns 403 invalidCredentials for a wrong (but well-formed) password")
    void wrongPassword() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("wrongpw"), UserRole.USER);

      expectInvalidCredentials(
          AuthControllerIT.this.login(user.getUsername(), OTHER_VALID_PASSWORD));
    }

    @Test
    @DisplayName(
        "returns 403 invalidCredentials for the seeded admin, so the admin row is resolvable")
    void wrongPasswordForSeededAdmin() throws Exception {
      expectInvalidCredentials(AuthControllerIT.this.login(SEEDED_ADMIN_USERNAME, VALID_PASSWORD));
    }

    @Test
    @DisplayName("looks the username up case-sensitively: another casing is invalidCredentials")
    void usernameLookupIsCaseSensitive() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("MixedCase"), UserRole.USER);
      assertThat(user.getUsername()).isNotEqualTo(user.getUsername().toLowerCase());

      expectInvalidCredentials(
          AuthControllerIT.this.login(user.getUsername().toLowerCase(), VALID_PASSWORD));
      expectInvalidCredentials(
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
    @DisplayName("accepts usernames at the form's length limits (then invalidCredentials)")
    void boundaryUsernames(final String name, final String username) throws Exception {
      expectInvalidCredentials(AuthControllerIT.this.login(username, VALID_PASSWORD));
    }

    static Stream<Arguments> boundaryUsernames() {
      return Stream.of(
          Arguments.of("3-char username", "abc"),
          Arguments.of("150-char username", "a".repeat(150)));
    }

    @Test
    @DisplayName("returns 415 unsupportedMediaType for an unsupported content type")
    void unsupportedContentType() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("plain"), UserRole.USER);

      expectUnsupportedMediaType(
          AuthControllerIT.this.perform(
              post("/api/auth/login")
                  .contentType(MediaType.TEXT_PLAIN)
                  .content(loginBody(user.getUsername(), VALID_PASSWORD))));
    }

    @Test
    @DisplayName("returns 415 unsupportedMediaType when no content type is sent")
    void missingContentType() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("notype"), UserRole.USER);

      expectUnsupportedMediaType(
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
          "invalidCredentials",
          "invalidCredentials",
          INVALID_CREDENTIALS_TEXT);
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
          "invalidCredentials",
          "invalidCredentials",
          INVALID_CREDENTIALS_TEXT);
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

      expectInvalidCredentials(AuthControllerIT.this.login(oldUsername, VALID_PASSWORD));
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

      expectInvalidCredentials(AuthControllerIT.this.login(user.getUsername(), VALID_PASSWORD));
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

      expectInvalidCredentials(AuthControllerIT.this.login(user.getUsername(), VALID_PASSWORD));
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
      final var refreshToken = AuthControllerIT.this.refreshTokenFor(user);

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
      // Carries the old username, and the version the rename below bumps the user to.
      final var refreshToken =
          AuthControllerIT.this.refreshTokenFor(user, Instant.now(), user.getTokenVersion() + 1);
      final var newUsername = uniqueUsername("after");
      AuthControllerIT.this.userTxService.updateUsername(user.getId(), newUsername);
      AuthControllerIT.this.entityManager.flush();

      final var before = Instant.now();
      final var body =
          expectSuccess(AuthControllerIT.this.refreshWith(refreshToken), "tokenRefreshed");

      AuthControllerIT.this.assertLoginInfo(body, user.getId(), newUsername, before, Instant.now());
    }

    @Test
    @DisplayName("returns 401 accessNotAllowed for an access token")
    void rejectsAnAccessTokenAsRefreshToken() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("access"), UserRole.USER);
      final var loginBody =
          expectSuccess(
              AuthControllerIT.this.login(user.getUsername(), VALID_PASSWORD), "loginSucceeded");
      final String accessToken = JsonPath.read(loginBody, "$.data.token");

      expectAccessNotAllowed(AuthControllerIT.this.refreshWith(accessToken));
    }

    @Test
    @DisplayName("returns 401 accessNotAllowed for a claim-less legacy token without an audience")
    void rejectsAClaimlessLegacyToken() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("legacy"), UserRole.USER);
      final var legacyToken =
          AuthControllerIT.this.claimlessToken(
              user.getId(), user.getUsername(), AuthUtils.TIMEOUT_REFRESH_TOKEN);

      expectAccessNotAllowed(AuthControllerIT.this.refreshWith(legacyToken));
    }

    @Test
    @DisplayName("returns 401 refreshTokenExpired for an expired token")
    void expiredToken() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("expired"), UserRole.USER);
      final var expired =
          AuthControllerIT.this.jwtUtils.createRefreshToken(
              user.getId(),
              user.getUsername(),
              Duration.ofSeconds(-30),
              Instant.now(),
              user.getTokenVersion());

      expectRefreshTokenExpired(AuthControllerIT.this.refreshWith(expired));
    }

    @Test
    @DisplayName("keeps the session start of the login across refreshes")
    void keepsTheSessionStart() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("keep"), UserRole.USER);
      final var sessionStart =
          Instant.now().minus(Duration.ofHours(3)).truncatedTo(ChronoUnit.SECONDS);
      final var refreshToken =
          AuthControllerIT.this.refreshTokenFor(user, sessionStart, user.getTokenVersion());

      final var first =
          expectSuccess(AuthControllerIT.this.refreshWith(refreshToken), "tokenRefreshed");
      final String firstRefreshToken = JsonPath.read(first, "$.data.refreshToken");
      final var second =
          expectSuccess(AuthControllerIT.this.refreshWith(firstRefreshToken), "tokenRefreshed");

      final String secondRefreshToken = JsonPath.read(second, "$.data.refreshToken");
      assertThat(
              AuthControllerIT.this.jwtUtils.verifyRefreshToken(secondRefreshToken).sessionStart())
          .isEqualTo(sessionStart);
      final String secondAccessToken = JsonPath.read(second, "$.data.token");
      assertThat(
              AuthControllerIT.this.jwtUtils.extractSessionStart(
                  AuthUtils.AUTH_BEARER + secondAccessToken))
          .isEqualTo(sessionStart);
    }

    @Test
    @DisplayName("issues tokens that expire no later than the session's absolute lifetime")
    void boundsTokensBySessionEnd() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("bound"), UserRole.USER);
      final var sessionStart =
          Instant.now()
              .minus(AuthUtils.TIMEOUT_SESSION)
              .plus(Duration.ofMinutes(10))
              .truncatedTo(ChronoUnit.SECONDS);
      final var sessionEnd = sessionStart.plus(AuthUtils.TIMEOUT_SESSION);
      final var refreshToken =
          AuthControllerIT.this.refreshTokenFor(user, sessionStart, user.getTokenVersion());

      final var body =
          expectSuccess(AuthControllerIT.this.refreshWith(refreshToken), "tokenRefreshed");

      // Regular lifetimes (30 / 60 minutes) would run past the session end, which is 10 minutes
      // away.
      final String newAccessToken = JsonPath.read(body, "$.data.token");
      final String newRefreshToken = JsonPath.read(body, "$.data.refreshToken");
      assertThat(JWT.decode(newAccessToken).getExpiresAtAsInstant()).isBeforeOrEqualTo(sessionEnd);
      assertThat(JWT.decode(newRefreshToken).getExpiresAtAsInstant()).isBeforeOrEqualTo(sessionEnd);
      assertThat(JWT.decode(newRefreshToken).getExpiresAtAsInstant()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("returns 401 refreshTokenExpired once the session's absolute lifetime has passed")
    void rejectsARefreshAfterSessionEnd() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("ended"), UserRole.USER);
      final var sessionStart =
          Instant.now()
              .minus(AuthUtils.TIMEOUT_SESSION)
              .minusSeconds(5)
              .truncatedTo(ChronoUnit.SECONDS);
      // The token itself is still within its own lifetime: only the session has ended.
      final var refreshToken =
          AuthControllerIT.this.refreshTokenFor(user, sessionStart, user.getTokenVersion());

      expectRefreshTokenExpired(AuthControllerIT.this.refreshWith(refreshToken));
    }

    @Test
    @DisplayName(
        "returns 401 refreshTokenExpired for a token issued before the user's token version")
    void rejectsATokenFromAnOlderVersion() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("revoked"), UserRole.USER);
      final var refreshToken = AuthControllerIT.this.refreshTokenFor(user);
      AuthControllerIT.this.userTxService.updatePassword(user.getId(), "newhash", "newsalt");
      AuthControllerIT.this.entityManager.flush();

      expectRefreshTokenExpired(AuthControllerIT.this.refreshWith(refreshToken));
    }

    @Test
    @DisplayName("returns 401 refreshTokenExpired once an admin has reset the user's password")
    void rejectsATokenAfterAnAdminPasswordReset() throws Exception {
      final var adminToken = AuthControllerIT.this.adminBearerToken();
      final var user = AuthControllerIT.this.createUser(uniqueUsername("reset"), UserRole.USER);
      final var refreshToken = AuthControllerIT.this.refreshTokenFor(user);
      expectSuccess(
          AuthControllerIT.this.perform(
              post("/api/users/" + user.getId() + "/actions/reset-password")
                  .header(AUTHORIZATION, adminToken)),
          "passwordReset");
      AuthControllerIT.this.entityManager.flush();

      expectRefreshTokenExpired(AuthControllerIT.this.refreshWith(refreshToken));
    }

    @Test
    @DisplayName("returns 401 refreshTokenExpired once an admin has renamed the user")
    void rejectsATokenAfterAnAdminRename() throws Exception {
      final var adminToken = AuthControllerIT.this.adminBearerToken();
      final var user = AuthControllerIT.this.createUser(uniqueUsername("rename"), UserRole.USER);
      final var refreshToken = AuthControllerIT.this.refreshTokenFor(user);
      expectSuccess(
          AuthControllerIT.this.perform(
              put("/api/users/" + user.getId())
                  .header(AUTHORIZATION, adminToken)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"username\":\"%s\",\"role\":\"USER\"}"
                          .formatted(uniqueUsername("renamed")))),
          "userUpdated");
      AuthControllerIT.this.entityManager.flush();

      expectRefreshTokenExpired(AuthControllerIT.this.refreshWith(refreshToken));
    }

    @Test
    @DisplayName("keeps a token valid when an admin edit leaves the username unchanged")
    void keepsATokenWhenTheUsernameIsUnchanged() throws Exception {
      final var adminToken = AuthControllerIT.this.adminBearerToken();
      final var user = AuthControllerIT.this.createUser(uniqueUsername("samename"), UserRole.USER);
      final var refreshToken = AuthControllerIT.this.refreshTokenFor(user);
      expectSuccess(
          AuthControllerIT.this.perform(
              put("/api/users/" + user.getId())
                  .header(AUTHORIZATION, adminToken)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"username\":\"%s\",\"role\":\"ADMIN\"}".formatted(user.getUsername()))),
          "userUpdated");
      AuthControllerIT.this.entityManager.flush();

      expectSuccess(AuthControllerIT.this.refreshWith(refreshToken), "tokenRefreshed");
    }

    @Test
    @DisplayName("returns 401 accessNotAllowed for a token without session claims")
    void rejectsATokenWithoutSessionClaims() throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("noclaims"), UserRole.USER);
      final var token =
          JWT.create()
              .withSubject(user.getId().toString())
              .withClaim("username", user.getUsername())
              .withClaim("token_type", "refresh")
              .withExpiresAt(Instant.now().plus(AuthUtils.TIMEOUT_REFRESH_TOKEN))
              .sign(Algorithm.HMAC512(AuthControllerIT.this.serverSecret()));

      expectAccessNotAllowed(AuthControllerIT.this.refreshWith(token));
    }

    @Test
    @DisplayName("returns 401 accessNotAllowed for a token that is not a JWT")
    void malformedToken() throws Exception {
      expectAccessNotAllowed(AuthControllerIT.this.refreshWith("not-a-jwt"));
    }

    @Test
    @DisplayName("returns 400 validationError for an empty token")
    void emptyToken() throws Exception {
      expectValidationError(AuthControllerIT.this.refreshWith(""));
    }

    @Test
    @DisplayName("returns 401 accessNotAllowed for a token whose payload was tampered with")
    void tamperedToken() throws Exception {
      final var victim = AuthControllerIT.this.createUser(uniqueUsername("victim"), UserRole.USER);
      final var attacker =
          AuthControllerIT.this.createUser(uniqueUsername("attack"), UserRole.USER);
      final var attackerToken = AuthControllerIT.this.refreshTokenFor(attacker);
      final var forged = withSubject(attackerToken, attacker.getId(), victim.getId());
      assertThat(forged).isNotEqualTo(attackerToken);

      expectAccessNotAllowed(AuthControllerIT.this.refreshWith(forged));
    }

    @Test
    @DisplayName("returns 401 accessNotAllowed for a token signed with a different secret")
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
    @DisplayName("returns 401 accessNotAllowed for an unsigned (alg=none) token")
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
    @DisplayName("returns 401 unAuthorized when the token's user does not exist")
    void unknownUser() throws Exception {
      final var token =
          AuthControllerIT.this.jwtUtils.createRefreshToken(
              UUID.randomUUID(),
              uniqueUsername("ghost"),
              AuthUtils.TIMEOUT_REFRESH_TOKEN,
              Instant.now(),
              0);

      expectUnauthorized(AuthControllerIT.this.refreshWith(token));
    }

    @Test
    @DisplayName("returns 401 unAuthorized once the token's user has been deleted")
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

      expectUnauthorized(AuthControllerIT.this.refreshWith(refreshToken));
    }

    @Test
    @DisplayName("returns 401 accessNotAllowed for a validly signed token whose subject is no UUID")
    void nonUuidSubject() throws Exception {
      final var token =
          signedRefreshToken(
              "not-a-uuid",
              "someuser",
              Instant.now().plus(AuthUtils.TIMEOUT_REFRESH_TOKEN),
              Algorithm.HMAC512(AuthControllerIT.this.serverSecret()));

      expectAccessNotAllowed(AuthControllerIT.this.refreshWith(token));
    }

    @Test
    @DisplayName("returns 401 accessNotAllowed for a validly signed token without a subject")
    void missingSubject() throws Exception {
      final var token =
          signedRefreshToken(
              null,
              "someuser",
              Instant.now().plus(AuthUtils.TIMEOUT_REFRESH_TOKEN),
              Algorithm.HMAC512(AuthControllerIT.this.serverSecret()));

      expectAccessNotAllowed(AuthControllerIT.this.refreshWith(token));
    }

    @Test
    @DisplayName("publishes no login event, neither when it rejects the token nor when it accepts")
    void publishesNoLoginEvent(final ApplicationEvents events) throws Exception {
      final var user = AuthControllerIT.this.createUser(uniqueUsername("noevent"), UserRole.USER);
      final var algorithm = Algorithm.HMAC512(AuthControllerIT.this.serverSecret());
      final var expiresAt = Instant.now().plus(AuthUtils.TIMEOUT_REFRESH_TOKEN);

      expectAccessNotAllowed(
          AuthControllerIT.this.refreshWith(
              signedRefreshToken(null, "someuser", expiresAt, algorithm)));
      expectAccessNotAllowed(
          AuthControllerIT.this.refreshWith(
              signedRefreshToken("not-a-uuid", "someuser", expiresAt, algorithm)));
      expectSuccess(
          AuthControllerIT.this.refreshWith(AuthControllerIT.this.refreshTokenFor(user)),
          "tokenRefreshed");

      assertThat(events.stream(UserLoginEvent.class)).isEmpty();

      // Control: the same recorder does see the event that a login publishes.
      expectSuccess(
          AuthControllerIT.this.login(user.getUsername(), VALID_PASSWORD), "loginSucceeded");

      assertThat(events.stream(UserLoginEvent.class))
          .extracting(UserLoginEvent::username)
          .containsExactly(user.getUsername());
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
    @DisplayName("returns 400 validationError when the form carries no usable refreshToken")
    void missingRefreshToken(final String name, final String body) throws Exception {
      expectValidationError(AuthControllerIT.this.refresh(body));
    }

    static Stream<Arguments> bodiesWithoutToken() {
      return Stream.of(
          Arguments.of("empty object", "{}"),
          Arguments.of("null refreshToken", "{\"refreshToken\":null}"),
          Arguments.of("empty refreshToken", "{\"refreshToken\":\"\"}"));
    }

    @Test
    @DisplayName("returns 401 accessNotAllowed when used as Bearer on an authenticated endpoint")
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
    @DisplayName("returns 415 unsupportedMediaType for an unsupported content type")
    void unsupportedContentType() throws Exception {
      expectUnsupportedMediaType(
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
