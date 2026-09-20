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
package io.repsy.os.shared.user.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Full-stack integration tests for {@code /api/users/*}, exercising the real Spring context, MVC
 * dispatch and a containerized PostgreSQL database (Flyway-migrated) end to end.
 *
 * <p>The container, the fake {@code multiport.ports.api} local port ({@link #apiPort()}, needed
 * because {@code UserController} is only registered on the "api" connector), the user and JWT
 * fixtures and the per-test rollback all come from {@link AbstractIntegrationTest}. The only data
 * that survives between tests is what the application seeds at startup (the {@code admin} user and
 * the default repos), so tests that list users scope their query with a unique {@code search} tag
 * instead of relying on the table being empty.
 */
@DisplayName("UserController /api/users/*")
class UserControllerIT extends AbstractIntegrationTest {

  private static final Map<String, String> SUCCESS_TEXTS =
      Map.of(
          "usersFetched", "Users fetched.",
          "userCreated", "User created.",
          "userUpdated", "User updated.",
          "userDeleted", "User deleted.",
          "passwordReset", "Password reset.");
  private static final String VALIDATION_TEXT = "Incoming data couldn't be validated.";
  private static final String UNSUPPORTED_MEDIA_TYPE_TEXT = "Unsupported media type.";
  private static final String USERNAME_IN_USE_TEXT = "Username is in use. Please try another one.";
  private static final String USER_NOT_FOUND_TEXT = "User not found.";
  private static final String UNAUTHORIZED_TEXT = "The user has logged in but has no permissions.";
  private static final Instant BASE_TIME = Instant.parse("2026-01-01T00:00:00Z");

  private static final String[] USER_KEYS = {"id", "username", "role", "createdAt", "lastLoginAt"};
  private static final String[] PAGE_KEYS = {"size", "number", "totalElements", "totalPages"};

  // ---------------------------------------------------------------------------------------------
  // Request / fixture helpers
  // ---------------------------------------------------------------------------------------------

  private static String createBody(
      final String username, final String password, final String role) {
    return "{\"username\":\"%s\",\"password\":\"%s\",\"role\":\"%s\"}"
        .formatted(username, password, role);
  }

  private static String updateBody(final String username, final String role) {
    return "{\"username\":\"%s\",\"role\":\"%s\"}".formatted(username, role);
  }

  /**
   * Creates a user and pins its {@code createdAt}, so ordering assertions do not depend on
   * wall-clock resolution. {@code @CreationTimestamp} overwrites the value on INSERT, hence the
   * bulk update afterwards.
   */
  private User createUserCreatedAt(
      final String username, final UserRole role, final Instant createdAt) {
    final var user = this.createUser(username, role);
    this.entityManager
        .createQuery("update User u set u.createdAt = :createdAt where u.id = :id")
        .setParameter("createdAt", createdAt)
        .setParameter("id", user.getId())
        .executeUpdate();
    return this.reload(user.getId());
  }

  /** Flushes pending changes and re-reads the row, so the result reflects what the DB stores. */
  private User reload(final UUID userId) {
    this.entityManager.flush();
    this.entityManager.clear();
    return this.userRepository.findById(userId).orElseThrow();
  }

  // ---------------------------------------------------------------------------------------------
  // Response helpers
  // ---------------------------------------------------------------------------------------------

  /** Asserts a 200 SUCCESS envelope whose {@code text} is the one {@link #SUCCESS_TEXTS} lists. */
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

  /** Asserts the complete {@code UserResponse} shape against the row as stored in the database. */
  private static void assertUserJson(final Map<String, Object> node, final User expected) {
    assertThat(node)
        .containsOnlyKeys(USER_KEYS)
        .containsEntry("id", expected.getId().toString())
        .containsEntry("username", expected.getUsername())
        .containsEntry("role", expected.getRole().name());
    assertThat(instantOrNull(node.get("createdAt"))).isEqualTo(expected.getCreatedAt());
    assertThat(instantOrNull(node.get("lastLoginAt"))).isEqualTo(expected.getLastLoginAt());
  }

  private static Instant instantOrNull(final Object json) {
    return json == null ? null : Instant.parse((String) json);
  }

  private static long number(final Object json) {
    return ((Number) json).longValue();
  }

  private static List<String> usernames(final String body) {
    final List<Map<String, Object>> content = JsonPath.read(body, "$.data.content");
    return content.stream().map(node -> (String) node.get("username")).toList();
  }

  // ---------------------------------------------------------------------------------------------
  // Authentication & authorization -- identical for every endpoint, so parameterized
  // ---------------------------------------------------------------------------------------------

  /** One row per {@code UserController} handler; {@code request} receives the target user id. */
  private record Endpoint(String name, Function<UUID, MockHttpServletRequestBuilder> request) {
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
          new Endpoint("GET /api/users", _ -> get("/api/users")),
          new Endpoint(
              "POST /api/users",
              _ ->
                  post("/api/users")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(createBody("probe" + randomTag(), VALID_PASSWORD, "USER"))),
          new Endpoint(
              "PUT /api/users/{userId}",
              id ->
                  put("/api/users/" + id)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(updateBody("probe" + randomTag(), "ADMIN"))),
          new Endpoint("DELETE /api/users/{userId}", id -> delete("/api/users/" + id)),
          new Endpoint(
              "POST /api/users/{userId}/actions/reset-password",
              id -> post("/api/users/" + id + "/actions/reset-password")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("returns 401 when the Authorization header is missing")
    void missingAuthorizationHeader(final Endpoint endpoint) throws Exception {
      expectError(
          UserControllerIT.this.perform(endpoint.request().apply(UUID.randomUUID())),
          HttpStatus.UNAUTHORIZED,
          "missingRequestHeader",
          "Authorization",
          "A required request header is missing.");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("returns 401 for a header without a Bearer prefix")
    void nonBearerAuthorizationHeader(final Endpoint endpoint) throws Exception {
      expectError(
          UserControllerIT.this.perform(
              endpoint.request().apply(UUID.randomUUID()).header(AUTHORIZATION, "Basic dXNlcjpw")),
          HttpStatus.UNAUTHORIZED,
          "accessNotAllowed",
          "accessNotAllowed",
          "Access isn't allowed.");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("returns 401 for a malformed/garbage bearer token")
    void malformedBearerToken(final Endpoint endpoint) throws Exception {
      expectError(
          UserControllerIT.this.perform(
              endpoint
                  .request()
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
      final var admin = UserControllerIT.this.createUser(uniqueUsername("expired"), UserRole.ADMIN);

      expectError(
          UserControllerIT.this.perform(
              endpoint
                  .request()
                  .apply(UUID.randomUUID())
                  .header(AUTHORIZATION, UserControllerIT.this.expiredBearerTokenFor(admin))),
          HttpStatus.UNAUTHORIZED,
          "sessionExpired",
          "sessionExpired",
          "Session expired.");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("returns 401 unAuthorized when the token's user no longer exists")
    void tokenUserNoLongerExists(final Endpoint endpoint) throws Exception {
      // Authentication resolves the caller by the token's username claim, not by its subject id.
      final var token =
          UserControllerIT.this.bearerTokenFor(UUID.randomUUID(), uniqueUsername("ghost"));

      expectError(
          UserControllerIT.this.perform(
              endpoint.request().apply(UUID.randomUUID()).header(AUTHORIZATION, token)),
          HttpStatus.UNAUTHORIZED,
          "unAuthorized",
          "unAuthorized",
          UNAUTHORIZED_TEXT);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("returns 403 accessDenied for a non-admin caller and changes nothing")
    void nonAdminCaller(final Endpoint endpoint) throws Exception {
      final var caller = UserControllerIT.this.createUser(uniqueUsername("plain"), UserRole.USER);
      final var target = UserControllerIT.this.createUser(uniqueUsername("target"), UserRole.USER);
      final var countBefore = UserControllerIT.this.userRepository.count();
      final var token = UserControllerIT.this.bearerTokenFor(caller);
      // Snapshot before the call: the entity is managed, so a stray mutation would show up on it.
      final var targetBefore = UserControllerIT.this.reload(target.getId());
      final var username = targetBefore.getUsername();
      final var role = targetBefore.getRole();
      final var hash = targetBefore.getHash();
      final var salt = targetBefore.getSalt();

      expectError(
          UserControllerIT.this.perform(
              endpoint.request().apply(target.getId()).header(AUTHORIZATION, token)),
          HttpStatus.FORBIDDEN,
          "accessDenied",
          "accessDenied",
          "Access Denied. Please check your credentials.");

      assertThat(UserControllerIT.this.userRepository.count()).isEqualTo(countBefore);
      final var targetAfter = UserControllerIT.this.reload(target.getId());
      assertThat(targetAfter.getUsername()).isEqualTo(username);
      assertThat(targetAfter.getRole()).isEqualTo(role);
      assertThat(targetAfter.getHash()).isEqualTo(hash);
      assertThat(targetAfter.getSalt()).isEqualTo(salt);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // GET /api/users
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("GET /api/users")
  class ListUsers {

    @Test
    @DisplayName("returns the full page envelope, newest first, with every user field")
    void returnsFullPageNewestFirst() throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();
      final var tag = randomTag();
      final var oldest =
          UserControllerIT.this.createUserCreatedAt(tag + "-oldest", UserRole.USER, BASE_TIME);
      final var middle =
          UserControllerIT.this.createUserCreatedAt(
              tag + "-middle", UserRole.ADMIN, BASE_TIME.plusSeconds(60));
      final var newest =
          UserControllerIT.this.createUserCreatedAt(
              tag + "-newest", UserRole.USER, BASE_TIME.plusSeconds(120));
      UserControllerIT.this.userTxService.updateLastLoginAt(middle.getUsername());
      final var expectedNewest = UserControllerIT.this.reload(newest.getId());
      final var expectedMiddle = UserControllerIT.this.reload(middle.getId());
      final var expectedOldest = UserControllerIT.this.reload(oldest.getId());
      assertThat(expectedMiddle.getLastLoginAt()).isNotNull();

      final var body =
          expectSuccess(
              UserControllerIT.this.perform(
                  get("/api/users").header(AUTHORIZATION, token).param("search", tag)),
              "usersFetched");

      final Map<String, Object> data = JsonPath.read(body, "$.data");
      assertThat(data).containsOnlyKeys("content", "page");

      final List<Map<String, Object>> content = JsonPath.read(body, "$.data.content");
      assertThat(content).hasSize(3);
      assertUserJson(content.get(0), expectedNewest);
      assertUserJson(content.get(1), expectedMiddle);
      assertUserJson(content.get(2), expectedOldest);
      // Only the middle user has ever logged in.
      assertThat(content.get(0)).containsEntry("lastLoginAt", null);
      assertThat(content.get(2)).containsEntry("lastLoginAt", null);

      final Map<String, Object> page = JsonPath.read(body, "$.data.page");
      assertThat(page).containsOnlyKeys(PAGE_KEYS);
      assertThat(number(page.get("size"))).isEqualTo(10);
      assertThat(number(page.get("number"))).isZero();
      assertThat(number(page.get("totalElements"))).isEqualTo(3);
      assertThat(number(page.get("totalPages"))).isEqualTo(1);
    }

    @Test
    @DisplayName("without a search term, includes the seeded admin and counts every user")
    void withoutSearchIncludesSeededAdmin() throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();
      final var seeded = UserControllerIT.this.reload(UserControllerIT.this.seededAdmin().getId());
      final var totalUsers = UserControllerIT.this.userRepository.count();

      final var body =
          expectSuccess(
              UserControllerIT.this.perform(
                  get("/api/users").header(AUTHORIZATION, token).param("size", "100")),
              "usersFetched");

      final List<Map<String, Object>> content = JsonPath.read(body, "$.data.content");
      assertThat(content).hasSize((int) totalUsers);
      final var seededNode =
          content.stream()
              .filter(node -> SEEDED_ADMIN_USERNAME.equals(node.get("username")))
              .findFirst()
              .orElseThrow();
      assertUserJson(seededNode, seeded);
      assertThat(seededNode).containsEntry("role", "ADMIN");

      final Map<String, Object> page = JsonPath.read(body, "$.data.page");
      assertThat(page).containsOnlyKeys(PAGE_KEYS);
      assertThat(number(page.get("size"))).isEqualTo(100);
      assertThat(number(page.get("totalElements"))).isEqualTo(totalUsers);
      assertThat(number(page.get("totalPages"))).isEqualTo(1);
    }

    @Test
    @DisplayName("search is a case-insensitive substring match on the username")
    void searchIsCaseInsensitiveSubstring() throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();
      final var tag = randomTag();
      UserControllerIT.this.createUserCreatedAt(tag + "-Alpha", UserRole.USER, BASE_TIME);
      UserControllerIT.this.createUserCreatedAt(
          tag + "-beta", UserRole.USER, BASE_TIME.plusSeconds(60));

      final var byTag =
          expectSuccess(
              UserControllerIT.this.perform(
                  get("/api/users")
                      .header(AUTHORIZATION, token)
                      .param("search", tag.toUpperCase())),
              "usersFetched");
      assertThat(usernames(byTag)).containsExactly(tag + "-beta", tag + "-Alpha");

      final var byFullName =
          expectSuccess(
              UserControllerIT.this.perform(
                  get("/api/users")
                      .header(AUTHORIZATION, token)
                      .param("search", (tag + "-ALPHA").toLowerCase())),
              "usersFetched");
      assertThat(usernames(byFullName)).containsExactly(tag + "-Alpha");
    }

    @Test
    @DisplayName("returns an empty page when nothing matches the search")
    void emptyWhenNothingMatches() throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();

      final var body =
          expectSuccess(
              UserControllerIT.this.perform(
                  get("/api/users")
                      .header(AUTHORIZATION, token)
                      .param("search", "nomatch" + randomTag())),
              "usersFetched");

      final Map<String, Object> data = JsonPath.read(body, "$.data");
      assertThat(data).containsOnlyKeys("content", "page");
      final List<Object> content = JsonPath.read(body, "$.data.content");
      assertThat(content).isEmpty();
      final Map<String, Object> page = JsonPath.read(body, "$.data.page");
      assertThat(page).containsOnlyKeys(PAGE_KEYS);
      assertThat(number(page.get("size"))).isEqualTo(10);
      assertThat(number(page.get("number"))).isZero();
      assertThat(number(page.get("totalElements"))).isZero();
      assertThat(number(page.get("totalPages"))).isZero();
    }

    @Test
    @DisplayName("paginates with page/size, including a page past the end")
    void paginates() throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();
      final var tag = randomTag();
      // u0 is the oldest, u4 the newest, so the list order is u4, u3, u2, u1, u0.
      for (var i = 0; i < 5; i++) {
        UserControllerIT.this.createUserCreatedAt(
            tag + "-u" + i, UserRole.USER, BASE_TIME.plusSeconds(i * 60L));
      }

      final var expectedByPage =
          Map.of(
              0, List.of(tag + "-u4", tag + "-u3"),
              1, List.of(tag + "-u2", tag + "-u1"),
              2, List.of(tag + "-u0"),
              3, List.<String>of());

      for (final var entry : expectedByPage.entrySet()) {
        final var body =
            expectSuccess(
                UserControllerIT.this.perform(
                    get("/api/users")
                        .header(AUTHORIZATION, token)
                        .param("search", tag)
                        .param("page", String.valueOf(entry.getKey()))
                        .param("size", "2")),
                "usersFetched");

        assertThat(usernames(body)).as("page %d", entry.getKey()).isEqualTo(entry.getValue());
        final Map<String, Object> page = JsonPath.read(body, "$.data.page");
        assertThat(page).containsOnlyKeys(PAGE_KEYS);
        assertThat(number(page.get("size"))).isEqualTo(2);
        assertThat(number(page.get("number"))).isEqualTo(entry.getKey().longValue());
        assertThat(number(page.get("totalElements"))).isEqualTo(5);
        assertThat(number(page.get("totalPages"))).isEqualTo(3);
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonNumericPagingParams")
    @DisplayName("returns 400 validationError naming the parameter when it is not a number")
    void nonNumericPagingParam(final String param) throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();

      expectError(
          UserControllerIT.this.perform(
              get("/api/users").header(AUTHORIZATION, token).param(param, "abc")),
          HttpStatus.BAD_REQUEST,
          "validationError",
          param,
          VALIDATION_TEXT);
    }

    static Stream<String> nonNumericPagingParams() {
      return Stream.of("page", "size");
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("outOfRangePagingParams")
    @DisplayName("returns 400 validationError naming the parameter when it is out of range")
    void outOfRangePagingParam(final String param, final String value) throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();

      expectError(
          UserControllerIT.this.perform(
              get("/api/users").header(AUTHORIZATION, token).param(param, value)),
          HttpStatus.BAD_REQUEST,
          "validationError",
          param,
          VALIDATION_TEXT);
    }

    static Stream<Arguments> outOfRangePagingParams() {
      return Stream.of(
          Arguments.of("page", "-1"),
          Arguments.of("size", "0"),
          Arguments.of("size", "-1"),
          Arguments.of("size", "101"));
    }

    @ParameterizedTest(name = "size={0}")
    @ValueSource(strings = {"1", "100"})
    @DisplayName("accepts the size bounds")
    void acceptsSizeBounds(final String size) throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();

      expectSuccess(
          UserControllerIT.this.perform(
              get("/api/users").header(AUTHORIZATION, token).param("size", size)),
          "usersFetched");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // POST /api/users
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("POST /api/users")
  class CreateUser {

    @ParameterizedTest(name = "{0}")
    @MethodSource("validRoles")
    @DisplayName("creates the user and returns the full UserResponse")
    void createsUser(final UserRole role) throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();
      final var username = uniqueUsername("created");
      final var password = "NewPassword2@";

      final var body =
          expectSuccess(
              UserControllerIT.this.perform(
                  post("/api/users")
                      .header(AUTHORIZATION, token)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(createBody(username, password, role.name()))),
              "userCreated");

      final Map<String, Object> data = JsonPath.read(body, "$.data");
      assertThat(data)
          .containsOnlyKeys(USER_KEYS)
          .containsEntry("username", username)
          .containsEntry("role", role.name())
          .containsEntry("lastLoginAt", null);
      assertThat((String) data.get("id")).matches(UUID_PATTERN);

      final var persisted =
          UserControllerIT.this.reload(
              UserControllerIT.this.userRepository.findByUsername(username).orElseThrow().getId());
      assertThat(persisted.getId()).hasToString((String) data.get("id"));
      assertThat(persisted.getRole()).isEqualTo(role);
      assertThat(persisted.getLastLoginAt()).isNull();
      assertThat(persisted.getCreatedAt()).isNotNull();
      // The response must carry the same createdAt that was persisted (RPS-846).
      assertThat(data.get("createdAt")).isNotNull();
      assertThat(instantOrNull(data.get("createdAt"))).isEqualTo(persisted.getCreatedAt());
      // The password is stored salted+hashed, never verbatim, and must verify against the input.
      assertThat(persisted.getHash()).isNotEqualTo(password);
      assertThat(PasswordHasher.matches(password, persisted.getHash(), persisted.getSalt()))
          .isTrue();
    }

    static Stream<UserRole> validRoles() {
      return Stream.of(UserRole.USER, UserRole.ADMIN);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("boundaryCredentials")
    @DisplayName("accepts credentials exactly at the length limits")
    void acceptsBoundaryLengths(final String name, final String username, final String password)
        throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();

      final var body =
          expectSuccess(
              UserControllerIT.this.perform(
                  post("/api/users")
                      .header(AUTHORIZATION, token)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(createBody(username, password, "USER"))),
              "userCreated");

      assertThat((String) JsonPath.read(body, "$.data.username")).isEqualTo(username);
      assertThat(UserControllerIT.this.userRepository.existsByUsername(username)).isTrue();
    }

    static Stream<Arguments> boundaryCredentials() {
      final var suffix = UUID.randomUUID().toString().replace("-", "");
      return Stream.of(
          Arguments.of("3-char username", suffix.substring(0, 3), VALID_PASSWORD),
          Arguments.of("25-char username", suffix.substring(0, 25), VALID_PASSWORD),
          Arguments.of("6-char password", "pwmin" + suffix.substring(0, 10), "Abcde1"),
          Arguments.of(
              "50-char password", "pwmax" + suffix.substring(0, 10), "Aa1" + "x".repeat(47)));
    }

    @Test
    @DisplayName("returns 400 usernameInUse when the username already exists")
    void usernameAlreadyTaken() throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();
      final var existing =
          UserControllerIT.this.createUser(uniqueUsername("existing"), UserRole.USER);
      final var countBefore = UserControllerIT.this.userRepository.count();

      expectError(
          UserControllerIT.this.perform(
              post("/api/users")
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(createBody(existing.getUsername(), VALID_PASSWORD, "ADMIN"))),
          HttpStatus.BAD_REQUEST,
          "usernameInUse",
          "usernameInUse",
          USERNAME_IN_USE_TEXT);

      assertThat(UserControllerIT.this.userRepository.count()).isEqualTo(countBefore);
    }

    @Test
    @DisplayName("treats usernames with different casing as distinct")
    void usernamesAreCaseSensitive() throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();
      final var username = uniqueUsername("Case");

      expectSuccess(
          UserControllerIT.this.perform(
              post("/api/users")
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(createBody(username, VALID_PASSWORD, "USER"))),
          "userCreated");

      final var lowerCaseUsername = username.toLowerCase(Locale.ROOT);
      assertThat(lowerCaseUsername).isNotEqualTo(username);

      expectSuccess(
          UserControllerIT.this.perform(
              post("/api/users")
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(createBody(lowerCaseUsername, VALID_PASSWORD, "USER"))),
          "userCreated");
    }

    @Test
    @DisplayName("returns 400 usernameInUse for the seeded admin's username")
    void seededAdminUsernameIsTaken() throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();

      expectError(
          UserControllerIT.this.perform(
              post("/api/users")
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(createBody(SEEDED_ADMIN_USERNAME, VALID_PASSWORD, "USER"))),
          HttpStatus.BAD_REQUEST,
          "usernameInUse",
          "usernameInUse",
          USERNAME_IN_USE_TEXT);
    }

    /** RPS-986: "anonymous" labels the token Docker hands to callers without credentials. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"anonymous", "docker", "repsy"})
    @DisplayName("returns 400 usernameInUse and creates nothing for a reserved username")
    void reservedUsername(final String username) throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();
      final var countBefore = UserControllerIT.this.userRepository.count();

      expectError(
          UserControllerIT.this.perform(
              post("/api/users")
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(createBody(username, VALID_PASSWORD, "USER"))),
          HttpStatus.BAD_REQUEST,
          "usernameInUse",
          "usernameInUse",
          USERNAME_IN_USE_TEXT);

      assertThat(UserControllerIT.this.userRepository.count()).isEqualTo(countBefore);
      assertThat(UserControllerIT.this.userRepository.existsByUsername(username)).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    @DisplayName("returns 400 validationError and creates nothing for an invalid body")
    void invalidBody(final String name, final String body) throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();
      final var countBefore = UserControllerIT.this.userRepository.count();

      expectValidationError(
          UserControllerIT.this.perform(
              post("/api/users")
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body)));

      assertThat(UserControllerIT.this.userRepository.count()).isEqualTo(countBefore);
    }

    static Stream<Arguments> invalidBodies() {
      final var ok = "validname";
      return Stream.of(
          Arguments.of("username too short", createBody("ab", VALID_PASSWORD, "USER")),
          Arguments.of("username too long", createBody("a".repeat(26), VALID_PASSWORD, "USER")),
          Arguments.of("password too short", createBody(ok, "Ab1de", "USER")),
          Arguments.of("password too long", createBody(ok, "Aa1" + "x".repeat(48), "USER")),
          Arguments.of("password without uppercase", createBody(ok, "lowercase1", "USER")),
          Arguments.of("password without lowercase", createBody(ok, "UPPERCASE1", "USER")),
          Arguments.of("password without digit", createBody(ok, "NoDigitsHere", "USER")),
          Arguments.of("password with whitespace", createBody(ok, "Has Space1", "USER")),
          Arguments.of("missing username", "{\"password\":\"Password1!\",\"role\":\"USER\"}"),
          Arguments.of("missing password", "{\"username\":\"validname\",\"role\":\"USER\"}"),
          Arguments.of("missing role", "{\"username\":\"validname\",\"password\":\"Password1!\"}"),
          Arguments.of(
              "null role",
              "{\"username\":\"validname\",\"password\":\"Password1!\",\"role\":null}"),
          Arguments.of("unknown role", createBody(ok, VALID_PASSWORD, "SUPERUSER")),
          Arguments.of("lower-case role", createBody(ok, VALID_PASSWORD, "admin")),
          Arguments.of("empty object", "{}"),
          Arguments.of("malformed JSON", "{not-json"),
          Arguments.of("empty body", ""));
    }

    @Test
    @DisplayName("returns 415 unsupportedMediaType for an unsupported content type")
    void unsupportedContentType() throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();

      expectUnsupportedMediaType(
          UserControllerIT.this.perform(
              post("/api/users")
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.TEXT_PLAIN)
                  .content(createBody(uniqueUsername("plain"), VALID_PASSWORD, "USER"))));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // PUT /api/users/{userId}
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("PUT /api/users/{userId}")
  class UpdateUser {

    @Test
    @DisplayName("updates username and role, returns the full UserResponse, keeps everything else")
    void updatesUsernameAndRole() throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();
      final var target =
          UserControllerIT.this.createUserCreatedAt(
              uniqueUsername("before"), UserRole.USER, BASE_TIME);
      UserControllerIT.this.userTxService.updateLastLoginAt(target.getUsername());
      final var before = UserControllerIT.this.reload(target.getId());
      final var hash = before.getHash();
      final var salt = before.getSalt();
      final var createdAt = before.getCreatedAt();
      final var lastLoginAt = before.getLastLoginAt();
      final var newUsername = uniqueUsername("after");

      final var body =
          expectSuccess(
              UserControllerIT.this.perform(
                  put("/api/users/" + target.getId())
                      .header(AUTHORIZATION, token)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(updateBody(newUsername, "ADMIN"))),
              "userUpdated");

      final var after = UserControllerIT.this.reload(target.getId());
      assertThat(after.getUsername()).isEqualTo(newUsername);
      assertThat(after.getRole()).isEqualTo(UserRole.ADMIN);
      assertThat(after.getHash()).isEqualTo(hash);
      assertThat(after.getSalt()).isEqualTo(salt);
      assertThat(after.getCreatedAt()).isEqualTo(createdAt);
      assertThat(after.getLastLoginAt()).isEqualTo(lastLoginAt);

      final Map<String, Object> data = JsonPath.read(body, "$.data");
      assertUserJson(data, after);
      assertThat(data.get("lastLoginAt")).isNotNull();
    }

    @Test
    @DisplayName("updates only the role when the username is unchanged (own name is not 'in use')")
    void updatesRoleKeepingUsername() throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();
      final var target = UserControllerIT.this.createUser(uniqueUsername("demote"), UserRole.ADMIN);

      final var body =
          expectSuccess(
              UserControllerIT.this.perform(
                  put("/api/users/" + target.getId())
                      .header(AUTHORIZATION, token)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(updateBody(target.getUsername(), "USER"))),
              "userUpdated");

      final var after = UserControllerIT.this.reload(target.getId());
      assertThat(after.getUsername()).isEqualTo(target.getUsername());
      assertThat(after.getRole()).isEqualTo(UserRole.USER);
      final Map<String, Object> data = JsonPath.read(body, "$.data");
      assertUserJson(data, after);
      assertThat(data).containsEntry("role", "USER").containsEntry("lastLoginAt", null);
    }

    @Test
    @DisplayName("returns 400 usernameInUse when the new username belongs to another user")
    void usernameTakenByAnotherUser() throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();
      final var other = UserControllerIT.this.createUser(uniqueUsername("other"), UserRole.USER);
      final var target = UserControllerIT.this.createUser(uniqueUsername("mine"), UserRole.USER);
      final var originalUsername = target.getUsername();

      expectError(
          UserControllerIT.this.perform(
              put("/api/users/" + target.getId())
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(updateBody(other.getUsername(), "ADMIN"))),
          HttpStatus.BAD_REQUEST,
          "usernameInUse",
          "usernameInUse",
          USERNAME_IN_USE_TEXT);

      final var after = UserControllerIT.this.reload(target.getId());
      assertThat(after.getUsername()).isEqualTo(originalUsername);
      assertThat(after.getRole()).isEqualTo(UserRole.USER);
    }

    @Test
    @DisplayName("returns 400 usernameInUse when the new username is reserved")
    void reservedNewUsername() throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();
      final var target = UserControllerIT.this.createUser(uniqueUsername("mine"), UserRole.USER);
      final var originalUsername = target.getUsername();

      expectError(
          UserControllerIT.this.perform(
              put("/api/users/" + target.getId())
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(updateBody("anonymous", "USER"))),
          HttpStatus.BAD_REQUEST,
          "usernameInUse",
          "usernameInUse",
          USERNAME_IN_USE_TEXT);

      assertThat(UserControllerIT.this.reload(target.getId()).getUsername())
          .isEqualTo(originalUsername);
    }

    @Test
    @DisplayName("lets a user who already holds a reserved username keep it")
    void existingReservedUsernameIsKept() throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();
      // Created before the name was reserved, so it bypasses the check like an upgraded instance.
      final var target = UserControllerIT.this.createUser("anonymous", UserRole.USER);

      expectSuccess(
          UserControllerIT.this.perform(
              put("/api/users/" + target.getId())
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(updateBody("anonymous", "ADMIN"))),
          "userUpdated");

      assertThat(UserControllerIT.this.reload(target.getId()).getRole()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    @DisplayName("returns 404 userNotFound for an unknown user id")
    void unknownUser() throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();

      expectError(
          UserControllerIT.this.perform(
              put("/api/users/" + UUID.randomUUID())
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(updateBody(uniqueUsername("nobody"), "USER"))),
          HttpStatus.NOT_FOUND,
          "userNotFound",
          "userNotFound",
          USER_NOT_FOUND_TEXT);
    }

    @Test
    @DisplayName("returns 400 validationError naming userId when the id is not a UUID")
    void malformedUserId() throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();

      expectError(
          UserControllerIT.this.perform(
              put("/api/users/not-a-uuid")
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(updateBody(uniqueUsername("nobody"), "USER"))),
          HttpStatus.BAD_REQUEST,
          "validationError",
          "userId",
          VALIDATION_TEXT);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    @DisplayName("returns 400 validationError and changes nothing for an invalid body")
    void invalidBody(final String name, final String body) throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();
      final var target = UserControllerIT.this.createUser(uniqueUsername("keep"), UserRole.USER);
      final var username = target.getUsername();

      expectValidationError(
          UserControllerIT.this.perform(
              put("/api/users/" + target.getId())
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body)));

      final var after = UserControllerIT.this.reload(target.getId());
      assertThat(after.getUsername()).isEqualTo(username);
      assertThat(after.getRole()).isEqualTo(UserRole.USER);
    }

    static Stream<Arguments> invalidBodies() {
      return Stream.of(
          Arguments.of("username too short", updateBody("ab", "ADMIN")),
          Arguments.of("username too long", updateBody("a".repeat(26), "ADMIN")),
          Arguments.of("missing username", "{\"role\":\"ADMIN\"}"),
          Arguments.of("missing role", "{\"username\":\"validname\"}"),
          Arguments.of("null role", "{\"username\":\"validname\",\"role\":null}"),
          Arguments.of("unknown role", updateBody("validname", "SUPERUSER")),
          Arguments.of("empty object", "{}"),
          Arguments.of("malformed JSON", "{not-json"),
          Arguments.of("empty body", ""));
    }

    @Test
    @DisplayName(
        "returns 400 cannotDemoteLastAdminUser for the last remaining ADMIN and changes nothing")
    void cannotDemoteLastAdmin() throws Exception {
      // The database is shared with other IT classes, so drop any other committed ADMIN first.
      final var lastAdmin = UserControllerIT.this.seededAdminAsLastAdmin();
      assertThat(UserControllerIT.this.userRepository.countByRole(UserRole.ADMIN)).isEqualTo(1L);
      final var token = UserControllerIT.this.bearerTokenFor(lastAdmin);

      // The body also renames the user, to prove a rejected request applies no part of the update.
      expectError(
          UserControllerIT.this.perform(
              put("/api/users/" + lastAdmin.getId())
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(updateBody(uniqueUsername("renamed"), "USER"))),
          HttpStatus.BAD_REQUEST,
          "cannotDemoteLastAdminUser",
          "cannotDemoteLastAdminUser",
          "You cannot remove the admin role from the last admin user.");

      final var after = UserControllerIT.this.reload(lastAdmin.getId());
      assertThat(after.getUsername()).isEqualTo(SEEDED_ADMIN_USERNAME);
      assertThat(after.getRole()).isEqualTo(UserRole.ADMIN);
      assertThat(UserControllerIT.this.userRepository.countByRole(UserRole.ADMIN)).isEqualTo(1L);
    }

    @Test
    @DisplayName("lets an admin demote themselves while another admin remains")
    void demotingSelfWithAnotherAdminIsAllowed() throws Exception {
      final var self =
          UserControllerIT.this.createUser(uniqueUsername("selfdemote"), UserRole.ADMIN);
      final var token = UserControllerIT.this.bearerTokenFor(self);
      assertThat(UserControllerIT.this.userRepository.countByRole(UserRole.ADMIN))
          .isGreaterThanOrEqualTo(2L);

      expectSuccess(
          UserControllerIT.this.perform(
              put("/api/users/" + self.getId())
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(updateBody(self.getUsername(), "USER"))),
          "userUpdated");

      assertThat(UserControllerIT.this.reload(self.getId()).getRole()).isEqualTo(UserRole.USER);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // DELETE /api/users/{userId}
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("DELETE /api/users/{userId}")
  class DeleteUser {

    @Test
    @DisplayName("deletes a regular user")
    void deletesUser() throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();
      final var target = UserControllerIT.this.createUser(uniqueUsername("delme"), UserRole.USER);

      final var body =
          expectSuccess(
              UserControllerIT.this.perform(
                  delete("/api/users/" + target.getId()).header(AUTHORIZATION, token)),
              "userDeleted");

      final Object data = JsonPath.read(body, "$.data");
      assertThat(data).isNull();
      UserControllerIT.this.entityManager.flush();
      assertThat(UserControllerIT.this.userRepository.findById(target.getId())).isEmpty();
    }

    @Test
    @DisplayName("deletes another ADMIN while other admins remain")
    void deletesNonLastAdmin() throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();
      final var target = UserControllerIT.this.createUser(uniqueUsername("admin2"), UserRole.ADMIN);

      expectSuccess(
          UserControllerIT.this.perform(
              delete("/api/users/" + target.getId()).header(AUTHORIZATION, token)),
          "userDeleted");

      UserControllerIT.this.entityManager.flush();
      assertThat(UserControllerIT.this.userRepository.findById(target.getId())).isEmpty();
    }

    @Test
    @DisplayName("lets an admin delete their own account; the still-valid token then gets 401")
    void deletingOwnAccountInvalidatesTheSession() throws Exception {
      final var admin = UserControllerIT.this.createUser(uniqueUsername("selfdel"), UserRole.ADMIN);
      final var token = UserControllerIT.this.bearerTokenFor(admin);

      expectSuccess(
          UserControllerIT.this.perform(
              delete("/api/users/" + admin.getId()).header(AUTHORIZATION, token)),
          "userDeleted");
      UserControllerIT.this.entityManager.flush();

      expectError(
          UserControllerIT.this.perform(get("/api/users").header(AUTHORIZATION, token)),
          HttpStatus.UNAUTHORIZED,
          "unAuthorized",
          "unAuthorized",
          UNAUTHORIZED_TEXT);
    }

    @Test
    @DisplayName("returns 400 cannotDeleteLastAdminUser for the last remaining ADMIN")
    void cannotDeleteLastAdmin() throws Exception {
      // The database is shared with other IT classes, so drop any other committed ADMIN first.
      final var lastAdmin = UserControllerIT.this.seededAdminAsLastAdmin();
      assertThat(UserControllerIT.this.userRepository.countByRole(UserRole.ADMIN)).isEqualTo(1L);
      final var token = UserControllerIT.this.bearerTokenFor(lastAdmin);

      expectError(
          UserControllerIT.this.perform(
              delete("/api/users/" + lastAdmin.getId()).header(AUTHORIZATION, token)),
          HttpStatus.BAD_REQUEST,
          "cannotDeleteLastAdminUser",
          "cannotDeleteLastAdminUser",
          "You cannot delete the last admin user.");

      UserControllerIT.this.entityManager.flush();
      assertThat(UserControllerIT.this.userRepository.findById(lastAdmin.getId())).isPresent();
    }

    @Test
    @DisplayName("returns 404 userNotFound for an unknown user id")
    void unknownUser() throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();

      expectError(
          UserControllerIT.this.perform(
              delete("/api/users/" + UUID.randomUUID()).header(AUTHORIZATION, token)),
          HttpStatus.NOT_FOUND,
          "userNotFound",
          "userNotFound",
          USER_NOT_FOUND_TEXT);
    }

    @Test
    @DisplayName("returns 404 userNotFound when deleting the same user twice")
    void deletingTwice() throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();
      final var target = UserControllerIT.this.createUser(uniqueUsername("twice"), UserRole.USER);
      expectSuccess(
          UserControllerIT.this.perform(
              delete("/api/users/" + target.getId()).header(AUTHORIZATION, token)),
          "userDeleted");
      UserControllerIT.this.entityManager.flush();

      expectError(
          UserControllerIT.this.perform(
              delete("/api/users/" + target.getId()).header(AUTHORIZATION, token)),
          HttpStatus.NOT_FOUND,
          "userNotFound",
          "userNotFound",
          USER_NOT_FOUND_TEXT);
    }

    @Test
    @DisplayName("returns 400 validationError naming userId when the id is not a UUID")
    void malformedUserId() throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();

      expectError(
          UserControllerIT.this.perform(
              delete("/api/users/not-a-uuid").header(AUTHORIZATION, token)),
          HttpStatus.BAD_REQUEST,
          "validationError",
          "userId",
          VALIDATION_TEXT);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // POST /api/users/{userId}/actions/reset-password
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("POST /api/users/{userId}/actions/reset-password")
  class ResetPassword {

    @Test
    @DisplayName("returns a generated password and stores its new salted hash")
    void resetsPassword() throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();
      final var target = UserControllerIT.this.createUser(uniqueUsername("resetme"), UserRole.USER);
      final var oldHash = target.getHash();
      final var oldSalt = target.getSalt();

      final var body =
          expectSuccess(
              UserControllerIT.this.perform(
                  post("/api/users/" + target.getId() + "/actions/reset-password")
                      .header(AUTHORIZATION, token)),
              "passwordReset");

      final String newPassword = JsonPath.read(body, "$.data");
      // PasswordGeneratorUtil: 12 chars with at least one lower, upper, digit and special char.
      assertThat(newPassword)
          .hasSize(12)
          .matches("^(?=.*\\d)(?=.*[a-z])(?=.*[A-Z])(?=.*[!@#$%^&*]).{12}$");

      final var after = UserControllerIT.this.reload(target.getId());
      assertThat(after.getHash()).isNotEqualTo(oldHash);
      assertThat(after.getSalt()).isNotEqualTo(oldSalt);
      assertThat(after.getUsername()).isEqualTo(target.getUsername());
      assertThat(after.getRole()).isEqualTo(UserRole.USER);
      assertThat(PasswordHasher.matches(newPassword, after.getHash(), after.getSalt())).isTrue();
      assertThat(PasswordHasher.matches(VALID_PASSWORD, after.getHash(), after.getSalt()))
          .as("the previous password must no longer work")
          .isFalse();
    }

    @Test
    @DisplayName("returns 404 userNotFound for an unknown user id")
    void unknownUser() throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();

      expectError(
          UserControllerIT.this.perform(
              post("/api/users/" + UUID.randomUUID() + "/actions/reset-password")
                  .header(AUTHORIZATION, token)),
          HttpStatus.NOT_FOUND,
          "userNotFound",
          "userNotFound",
          USER_NOT_FOUND_TEXT);
    }

    @Test
    @DisplayName("returns 400 validationError naming userId when the id is not a UUID")
    void malformedUserId() throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();

      expectError(
          UserControllerIT.this.perform(
              post("/api/users/not-a-uuid/actions/reset-password").header(AUTHORIZATION, token)),
          HttpStatus.BAD_REQUEST,
          "validationError",
          "userId",
          VALIDATION_TEXT);
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
     * answers with 404 {@code itemNotFound}.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedMethods")
    @DisplayName("answers 404 itemNotFound for a route or verb nothing maps")
    void unsupportedMethod(final String name, final MockHttpServletRequestBuilder request)
        throws Exception {
      final var token = UserControllerIT.this.adminBearerToken();

      expectError(
          UserControllerIT.this.perform(request.header(AUTHORIZATION, token)),
          HttpStatus.NOT_FOUND,
          "itemNotFound",
          null,
          "The requested item is not found.");
    }

    static Stream<Arguments> unsupportedMethods() {
      final var id = UUID.randomUUID();
      return Stream.of(
          Arguments.of("GET /api/users/{userId}", get("/api/users/" + id)),
          Arguments.of("PATCH /api/users/{userId}", patch("/api/users/" + id)),
          Arguments.of("DELETE /api/users", delete("/api/users")),
          Arguments.of("PUT /api/users", put("/api/users")),
          Arguments.of("GET /api/no-such-route", get("/api/no-such-route")));
    }
  }
}
