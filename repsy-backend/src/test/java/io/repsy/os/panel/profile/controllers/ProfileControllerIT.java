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
package io.repsy.os.panel.profile.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.user.entities.UserRole;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Full-stack integration tests for {@code /api/profile/*}, exercising the real Spring context, MVC
 * dispatch and a containerized PostgreSQL database (Flyway-migrated) end to end.
 *
 * <p>The container, the fake {@code multiport.ports.api} local port ({@link #apiPort()}, needed
 * because {@code ProfileController} is only registered on the "api" connector), the user and JWT
 * fixtures and the per-test rollback all come from {@link AbstractIntegrationTest}.
 */
@DisplayName("ProfileController /api/profile/*")
class ProfileControllerIT extends AbstractIntegrationTest {

  /**
   * A bearer token signed with the running application's secret, so it passes signature
   * verification. A {@code null} subject omits the claim altogether.
   */
  private String serverSignedBearerToken(final String subject) {
    final var secret = (String) ReflectionTestUtils.getField(this.jwtUtils, "secret");
    var builder =
        JWT.create()
            .withClaim("username", "someuser")
            .withExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
    if (subject != null) {
      builder = builder.withSubject(subject);
    }
    return AuthUtils.AUTH_BEARER + builder.sign(Algorithm.HMAC512(secret));
  }

  @Nested
  @DisplayName("GET /api/profile")
  class Get {

    @Test
    @DisplayName("returns the full profile of the authenticated user, including the diskUsage gap")
    void returnsProfileForAuthenticatedUser() throws Exception {
      final var user = ProfileControllerIT.this.createUser(uniqueUsername("getme"), UserRole.USER);
      final var token = ProfileControllerIT.this.bearerTokenFor(user.getId(), user.getUsername());

      final var body =
          ProfileControllerIT.this
              .mockMvc
              .perform(get("/api/profile").with(apiPort()).header(AUTHORIZATION, token))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.*", hasSize(5)))
              .andExpect(jsonPath("$.msgId").value("profileFetched"))
              .andExpect(jsonPath("$.type").value("SUCCESS"))
              .andExpect(jsonPath("$.errorCode").value(nullValue()))
              // "profileFetched" has no entry in messages.properties, so text falls back to the
              // msgId itself.
              .andExpect(jsonPath("$.text").value("profileFetched"))
              .andExpect(jsonPath("$.data.*", hasSize(6)))
              .andExpect(jsonPath("$.data.id").value(user.getId().toString()))
              .andExpect(jsonPath("$.data.username").value(user.getUsername()))
              .andExpect(jsonPath("$.data.role").value("USER"))
              // ProfileService.getProfile() never populates diskUsage, even though the OpenAPI
              // schema marks it required -- it always serializes as null. Real gap, asserted
              // deliberately.
              .andExpect(jsonPath("$.data.diskUsage").value(nullValue()))
              // Freshly created user has never logged in.
              .andExpect(jsonPath("$.data.lastLoginAt").value(nullValue()))
              .andExpect(jsonPath("$.data.createdAt").value(notNullValue()))
              .andReturn()
              .getResponse()
              .getContentAsString();

      final String createdAt = JsonPath.read(body, "$.data.createdAt");
      assertThat(Instant.parse(createdAt))
          .isCloseTo(user.getCreatedAt(), within(2, ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("returns lastLoginAt when the user has logged in before")
    void returnsLastLoginAtWhenPresent() throws Exception {
      final var user =
          ProfileControllerIT.this.createUser(uniqueUsername("hasLogin"), UserRole.USER);
      ProfileControllerIT.this.userTxService.updateLastLoginAt(user.getUsername());
      final var refreshed =
          ProfileControllerIT.this.userRepository.findById(user.getId()).orElseThrow();
      final var token = ProfileControllerIT.this.bearerTokenFor(user.getId(), user.getUsername());

      final var body =
          ProfileControllerIT.this
              .mockMvc
              .perform(get("/api/profile").with(apiPort()).header(AUTHORIZATION, token))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.lastLoginAt").value(notNullValue()))
              .andReturn()
              .getResponse()
              .getContentAsString();

      final String lastLoginAt = JsonPath.read(body, "$.data.lastLoginAt");
      assertThat(Instant.parse(lastLoginAt))
          .isCloseTo(refreshed.getLastLoginAt(), within(2, ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("returns 401 when the Authorization header is missing")
    void missingAuthorizationHeader() throws Exception {
      ProfileControllerIT.this
          .mockMvc
          .perform(get("/api/profile").with(apiPort()))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.*", hasSize(5)))
          .andExpect(jsonPath("$.msgId").value("missingRequestHeader"))
          .andExpect(jsonPath("$.type").value("ERROR"))
          .andExpect(jsonPath("$.data").value("Authorization"))
          .andExpect(
              jsonPath("$.errorCode")
                  .value(
                      matchesPattern(
                          "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
          .andExpect(jsonPath("$.text").value("A required request header is missing."));
    }

    @Test
    @DisplayName("returns 401 for a header without a Bearer prefix")
    void nonBearerAuthorizationHeader() throws Exception {
      ProfileControllerIT.this
          .mockMvc
          .perform(get("/api/profile").with(apiPort()).header(AUTHORIZATION, "Basic dXNlcjpwYXNz"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.msgId").value("accessNotAllowed"))
          .andExpect(jsonPath("$.data").value("accessNotAllowed"))
          .andExpect(jsonPath("$.text").value("Access isn't allowed."))
          .andExpect(
              jsonPath("$.errorCode")
                  .value(
                      matchesPattern(
                          "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")));
    }

    @Test
    @DisplayName("returns 401 for a malformed/garbage bearer token")
    void malformedBearerToken() throws Exception {
      ProfileControllerIT.this
          .mockMvc
          .perform(get("/api/profile").with(apiPort()).header(AUTHORIZATION, "Bearer not-a-jwt"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.msgId").value("accessNotAllowed"))
          .andExpect(jsonPath("$.text").value("Access isn't allowed."));
    }

    @Test
    @DisplayName("returns 401 for an expired token")
    void expiredToken() throws Exception {
      final var user =
          ProfileControllerIT.this.createUser(uniqueUsername("expired"), UserRole.USER);
      final var token = ProfileControllerIT.this.expiredBearerTokenFor(user);

      ProfileControllerIT.this
          .mockMvc
          .perform(get("/api/profile").with(apiPort()).header(AUTHORIZATION, token))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.msgId").value("sessionExpired"))
          .andExpect(jsonPath("$.data").value("sessionExpired"))
          .andExpect(jsonPath("$.text").value("Session expired."));
    }

    @Test
    @DisplayName("returns 401 accessNotAllowed for a validly signed token whose subject is no UUID")
    void nonUuidSubject() throws Exception {
      final var token = ProfileControllerIT.this.serverSignedBearerToken("not-a-uuid");

      ProfileControllerIT.this
          .mockMvc
          .perform(get("/api/profile").with(apiPort()).header(AUTHORIZATION, token))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.msgId").value("accessNotAllowed"))
          .andExpect(jsonPath("$.data").value("accessNotAllowed"))
          .andExpect(jsonPath("$.text").value("Access isn't allowed."));
    }

    @Test
    @DisplayName("returns 401 accessNotAllowed for a validly signed token without a subject")
    void missingSubject() throws Exception {
      final var token = ProfileControllerIT.this.serverSignedBearerToken(null);

      ProfileControllerIT.this
          .mockMvc
          .perform(get("/api/profile").with(apiPort()).header(AUTHORIZATION, token))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.msgId").value("accessNotAllowed"))
          .andExpect(jsonPath("$.data").value("accessNotAllowed"))
          .andExpect(jsonPath("$.text").value("Access isn't allowed."));
    }

    @Test
    @DisplayName("returns 404 when the user from the token no longer exists")
    void userNotFound() throws Exception {
      final var token = ProfileControllerIT.this.bearerTokenFor(UUID.randomUUID(), "ghost");

      ProfileControllerIT.this
          .mockMvc
          .perform(get("/api/profile").with(apiPort()).header(AUTHORIZATION, token))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.msgId").value("userNotFound"))
          .andExpect(jsonPath("$.data").value("userNotFound"))
          .andExpect(jsonPath("$.text").value("User not found."));
    }
  }

  @Nested
  @DisplayName("PUT /api/profile/username")
  class UpdateUsername {

    private static String body(final String username) {
      return "{\"username\":\"%s\"}".formatted(username);
    }

    @Test
    @DisplayName("updates the username and returns freshly minted tokens")
    void updatesUsername() throws Exception {
      final var user =
          ProfileControllerIT.this.createUser(uniqueUsername("oldname"), UserRole.USER);
      final var token = ProfileControllerIT.this.bearerTokenFor(user.getId(), user.getUsername());
      final var newUsername = uniqueUsername("newname");

      final var responseBody =
          ProfileControllerIT.this
              .mockMvc
              .perform(
                  put("/api/profile/username")
                      .with(apiPort())
                      .header(AUTHORIZATION, token)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(body(newUsername)))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.*", hasSize(5)))
              .andExpect(jsonPath("$.msgId").value("usernameUpdated"))
              .andExpect(jsonPath("$.type").value("SUCCESS"))
              .andExpect(jsonPath("$.errorCode").value(nullValue()))
              .andExpect(jsonPath("$.text").value("Username successfully updated."))
              .andExpect(jsonPath("$.data.*", hasSize(3)))
              .andExpect(jsonPath("$.data.username").value(newUsername))
              .andExpect(jsonPath("$.data.token").value(notNullValue()))
              .andExpect(jsonPath("$.data.refreshToken").value(notNullValue()))
              .andReturn()
              .getResponse()
              .getContentAsString();

      final String accessToken = JsonPath.read(responseBody, "$.data.token");
      final String refreshToken = JsonPath.read(responseBody, "$.data.refreshToken");

      final var decodedAccess = JWT.decode(accessToken);
      assertThat(decodedAccess.getSubject()).isEqualTo(user.getId().toString());
      assertThat(decodedAccess.getClaim("username").asString()).isEqualTo(newUsername);
      assertThat(decodedAccess.getExpiresAtAsInstant())
          .isCloseTo(Instant.now().plus(30, ChronoUnit.MINUTES), within(1, ChronoUnit.MINUTES));

      final var decodedRefresh = JWT.decode(refreshToken);
      assertThat(decodedRefresh.getSubject()).isEqualTo(user.getId().toString());
      assertThat(decodedRefresh.getExpiresAtAsInstant())
          .isCloseTo(Instant.now().plus(60, ChronoUnit.MINUTES), within(1, ChronoUnit.MINUTES));

      final var persisted =
          ProfileControllerIT.this.userRepository.findById(user.getId()).orElseThrow();
      assertThat(persisted.getUsername()).isEqualTo(newUsername);
    }

    @Test
    @DisplayName("returns 400 usernameInUse for a reserved username")
    void reservedUsername() throws Exception {
      final var user = ProfileControllerIT.this.createUser(uniqueUsername("resv"), UserRole.USER);
      final var token = ProfileControllerIT.this.bearerTokenFor(user.getId(), user.getUsername());

      ProfileControllerIT.this
          .mockMvc
          .perform(
              put("/api/profile/username")
                  .with(apiPort())
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  // "repsy" is seeded into reserved_username by V0001__Initial_Schema.sql.
                  .content(body("repsy")))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.msgId").value("usernameInUse"))
          .andExpect(jsonPath("$.data").value("usernameInUse"))
          .andExpect(jsonPath("$.text").value("Username is in use. Please try another one."));
    }

    @Test
    @DisplayName("returns 400 usernameInUse when the username belongs to another user")
    void usernameTakenByAnotherUser() throws Exception {
      final var other = ProfileControllerIT.this.createUser(uniqueUsername("taken"), UserRole.USER);
      final var user = ProfileControllerIT.this.createUser(uniqueUsername("wants"), UserRole.USER);
      final var token = ProfileControllerIT.this.bearerTokenFor(user.getId(), user.getUsername());

      ProfileControllerIT.this
          .mockMvc
          .perform(
              put("/api/profile/username")
                  .with(apiPort())
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body(other.getUsername())))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.msgId").value("usernameInUse"));
    }

    @Test
    @DisplayName(
        "returns 400 usernameInUse when re-submitting the user's own current username"
            + " (existsByUsername matches the caller's own row -- a real quirk, not a desired 200)")
    void reSubmittingOwnCurrentUsernameIsRejected() throws Exception {
      final var user =
          ProfileControllerIT.this.createUser(uniqueUsername("samesame"), UserRole.USER);
      final var token = ProfileControllerIT.this.bearerTokenFor(user.getId(), user.getUsername());

      ProfileControllerIT.this
          .mockMvc
          .perform(
              put("/api/profile/username")
                  .with(apiPort())
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body(user.getUsername())))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.msgId").value("usernameInUse"));
    }

    @Test
    @DisplayName("returns 400 validationError for a too-short username")
    void tooShortUsername() throws Exception {
      final var user = ProfileControllerIT.this.createUser(uniqueUsername("short"), UserRole.USER);
      final var token = ProfileControllerIT.this.bearerTokenFor(user.getId(), user.getUsername());

      ProfileControllerIT.this
          .mockMvc
          .perform(
              put("/api/profile/username")
                  .with(apiPort())
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body("ab")))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.msgId").value("validationError"))
          .andExpect(jsonPath("$.data").value(nullValue()))
          .andExpect(jsonPath("$.text").value("Incoming data couldn't be validated."));
    }

    @Test
    @DisplayName("returns 400 validationError for a too-long username")
    void tooLongUsername() throws Exception {
      final var user = ProfileControllerIT.this.createUser(uniqueUsername("long"), UserRole.USER);
      final var token = ProfileControllerIT.this.bearerTokenFor(user.getId(), user.getUsername());

      ProfileControllerIT.this
          .mockMvc
          .perform(
              put("/api/profile/username")
                  .with(apiPort())
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body("a".repeat(26))))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.msgId").value("validationError"));
    }

    @Test
    @DisplayName("returns 400 validationError for an uppercase/invalid-pattern username")
    void invalidPatternUsername() throws Exception {
      final var user =
          ProfileControllerIT.this.createUser(uniqueUsername("pattern"), UserRole.USER);
      final var token = ProfileControllerIT.this.bearerTokenFor(user.getId(), user.getUsername());

      ProfileControllerIT.this
          .mockMvc
          .perform(
              put("/api/profile/username")
                  .with(apiPort())
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body("Has-Upper-Case")))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.msgId").value("validationError"));
    }

    @Test
    @DisplayName("returns 400 validationError for malformed request JSON")
    void malformedJsonBody() throws Exception {
      final var user =
          ProfileControllerIT.this.createUser(uniqueUsername("badjson"), UserRole.USER);
      final var token = ProfileControllerIT.this.bearerTokenFor(user.getId(), user.getUsername());

      ProfileControllerIT.this
          .mockMvc
          .perform(
              put("/api/profile/username")
                  .with(apiPort())
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{not-json"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.msgId").value("validationError"));
    }

    @Test
    @DisplayName("returns 401 when the Authorization header is missing")
    void missingAuthorizationHeader() throws Exception {
      ProfileControllerIT.this
          .mockMvc
          .perform(
              put("/api/profile/username")
                  .with(apiPort())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body(uniqueUsername("nobody"))))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.msgId").value("missingRequestHeader"))
          .andExpect(jsonPath("$.text").value("A required request header is missing."));
    }

    @Test
    @DisplayName(
        "returns 401 for an expired token (extractUserId verifies internally, same as GET)")
    void expiredToken() throws Exception {
      final var user = ProfileControllerIT.this.createUser(uniqueUsername("expusr"), UserRole.USER);
      final var token = ProfileControllerIT.this.expiredBearerTokenFor(user);

      ProfileControllerIT.this
          .mockMvc
          .perform(
              put("/api/profile/username")
                  .with(apiPort())
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body(uniqueUsername("newone"))))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.msgId").value("sessionExpired"));
    }

    @Test
    @DisplayName("returns 404 when the user from the token no longer exists")
    void userNotFound() throws Exception {
      final var token = ProfileControllerIT.this.bearerTokenFor(UUID.randomUUID(), "ghost");

      ProfileControllerIT.this
          .mockMvc
          .perform(
              put("/api/profile/username")
                  .with(apiPort())
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body(uniqueUsername("newone"))))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.msgId").value("userNotFound"));
    }
  }

  @Nested
  @DisplayName("PUT /api/profile/password")
  class UpdatePassword {

    private static String body(final String password) {
      return "{\"password\":\"%s\"}".formatted(password);
    }

    @Test
    @DisplayName("changes the password and persists a new hash/salt")
    void updatesPassword() throws Exception {
      final var user = ProfileControllerIT.this.createUser(uniqueUsername("pwuser"), UserRole.USER);
      final var originalHash = user.getHash();
      final var token = ProfileControllerIT.this.bearerTokenFor(user.getId(), user.getUsername());
      final var newPassword = "NewPassword2@";

      ProfileControllerIT.this
          .mockMvc
          .perform(
              put("/api/profile/password")
                  .with(apiPort())
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body(newPassword)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.*", hasSize(5)))
          .andExpect(jsonPath("$.msgId").value("passwordChanged"))
          .andExpect(jsonPath("$.type").value("SUCCESS"))
          .andExpect(jsonPath("$.data").value(nullValue()))
          .andExpect(jsonPath("$.errorCode").value(nullValue()))
          .andExpect(jsonPath("$.text").value("Password changed."));

      final var persisted =
          ProfileControllerIT.this.userRepository.findById(user.getId()).orElseThrow();
      assertThat(persisted.getHash()).isNotEqualTo(originalHash);
      assertThat(AuthUtils.checkPassword(persisted.getHash(), persisted.getSalt(), newPassword))
          .isTrue();
    }

    @Test
    @DisplayName("returns 400 validationError for a too-short password")
    void tooShortPassword() throws Exception {
      final var user =
          ProfileControllerIT.this.createUser(uniqueUsername("shortpw"), UserRole.USER);
      final var token = ProfileControllerIT.this.bearerTokenFor(user.getId(), user.getUsername());

      ProfileControllerIT.this
          .mockMvc
          .perform(
              put("/api/profile/password")
                  .with(apiPort())
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body("Ab1")))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.msgId").value("validationError"))
          .andExpect(jsonPath("$.text").value("Incoming data couldn't be validated."));
    }

    @Test
    @DisplayName("returns 400 validationError for a too-long password")
    void tooLongPassword() throws Exception {
      final var user = ProfileControllerIT.this.createUser(uniqueUsername("longpw"), UserRole.USER);
      final var token = ProfileControllerIT.this.bearerTokenFor(user.getId(), user.getUsername());

      ProfileControllerIT.this
          .mockMvc
          .perform(
              put("/api/profile/password")
                  .with(apiPort())
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body("Aa1" + "x".repeat(48))))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.msgId").value("validationError"));
    }

    @Test
    @DisplayName("returns 400 validationError when missing an uppercase letter")
    void missingUppercase() throws Exception {
      final var user = ProfileControllerIT.this.createUser(uniqueUsername("nouppr"), UserRole.USER);
      final var token = ProfileControllerIT.this.bearerTokenFor(user.getId(), user.getUsername());

      ProfileControllerIT.this
          .mockMvc
          .perform(
              put("/api/profile/password")
                  .with(apiPort())
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body("lowercase1")))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.msgId").value("validationError"));
    }

    @Test
    @DisplayName("returns 400 validationError when missing a digit")
    void missingDigit() throws Exception {
      final var user =
          ProfileControllerIT.this.createUser(uniqueUsername("nodigit"), UserRole.USER);
      final var token = ProfileControllerIT.this.bearerTokenFor(user.getId(), user.getUsername());

      ProfileControllerIT.this
          .mockMvc
          .perform(
              put("/api/profile/password")
                  .with(apiPort())
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body("NoDigitsHere")))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.msgId").value("validationError"));
    }

    @Test
    @DisplayName("returns 400 validationError when the password contains whitespace")
    void containsWhitespace() throws Exception {
      final var user = ProfileControllerIT.this.createUser(uniqueUsername("wspw"), UserRole.USER);
      final var token = ProfileControllerIT.this.bearerTokenFor(user.getId(), user.getUsername());

      ProfileControllerIT.this
          .mockMvc
          .perform(
              put("/api/profile/password")
                  .with(apiPort())
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body("Has Space1")))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.msgId").value("validationError"));
    }

    @Test
    @DisplayName("returns 401 when the Authorization header is missing")
    void missingAuthorizationHeader() throws Exception {
      ProfileControllerIT.this
          .mockMvc
          .perform(
              put("/api/profile/password")
                  .with(apiPort())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body("NewPassword2@")))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.msgId").value("missingRequestHeader"))
          .andExpect(jsonPath("$.text").value("A required request header is missing."));
    }

    @Test
    @DisplayName("returns 404 when the user from the token no longer exists")
    void userNotFound() throws Exception {
      final var token = ProfileControllerIT.this.bearerTokenFor(UUID.randomUUID(), "ghost");

      ProfileControllerIT.this
          .mockMvc
          .perform(
              put("/api/profile/password")
                  .with(apiPort())
                  .header(AUTHORIZATION, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body("NewPassword2@")))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.msgId").value("userNotFound"));
    }
  }

  @Nested
  @DisplayName("DELETE /api/profile")
  class DeleteProfile {

    @Test
    @DisplayName("deletes a regular USER account")
    void deletesRegularUser() throws Exception {
      final var user = ProfileControllerIT.this.createUser(uniqueUsername("delme"), UserRole.USER);
      final var token = ProfileControllerIT.this.bearerTokenFor(user.getId(), user.getUsername());

      ProfileControllerIT.this
          .mockMvc
          .perform(delete("/api/profile").with(apiPort()).header(AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.*", hasSize(5)))
          .andExpect(jsonPath("$.msgId").value("profileDeleted"))
          .andExpect(jsonPath("$.type").value("SUCCESS"))
          .andExpect(jsonPath("$.data").value(nullValue()))
          .andExpect(jsonPath("$.errorCode").value(nullValue()))
          .andExpect(jsonPath("$.text").value("Profile account deleted."));

      assertThat(ProfileControllerIT.this.userRepository.findById(user.getId())).isEmpty();
    }

    @Test
    @DisplayName("deletes an ADMIN account when it is not the last one")
    void deletesNonLastAdmin() throws Exception {
      final var secondAdmin =
          ProfileControllerIT.this.createUser(uniqueUsername("admin2"), UserRole.ADMIN);
      final var token =
          ProfileControllerIT.this.bearerTokenFor(secondAdmin.getId(), secondAdmin.getUsername());

      ProfileControllerIT.this
          .mockMvc
          .perform(delete("/api/profile").with(apiPort()).header(AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.msgId").value("profileDeleted"));

      assertThat(ProfileControllerIT.this.userRepository.findById(secondAdmin.getId())).isEmpty();
    }

    @Test
    @DisplayName("returns 400 cannotDeleteLastAdminUser for the last remaining ADMIN")
    void cannotDeleteLastAdmin() throws Exception {
      // AdminUserInitializer seeds exactly one "admin" ADMIN user at application startup;
      // no other admin exists unless a test creates one (and @Transactional rolls that back).
      final var lastAdmin =
          ProfileControllerIT.this.userRepository.findByUsername("admin").orElseThrow();
      final var token =
          ProfileControllerIT.this.bearerTokenFor(lastAdmin.getId(), lastAdmin.getUsername());

      ProfileControllerIT.this
          .mockMvc
          .perform(delete("/api/profile").with(apiPort()).header(AUTHORIZATION, token))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.msgId").value("cannotDeleteLastAdminUser"))
          .andExpect(jsonPath("$.data").value("cannotDeleteLastAdminUser"))
          .andExpect(jsonPath("$.text").value("You cannot delete the last admin user."));

      assertThat(ProfileControllerIT.this.userRepository.findById(lastAdmin.getId())).isPresent();
    }

    @Test
    @DisplayName("returns 401 when the Authorization header is missing")
    void missingAuthorizationHeader() throws Exception {
      ProfileControllerIT.this
          .mockMvc
          .perform(delete("/api/profile").with(apiPort()))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.msgId").value("missingRequestHeader"))
          .andExpect(jsonPath("$.text").value("A required request header is missing."));
    }

    @Test
    @DisplayName("returns 401 for an expired token")
    void expiredToken() throws Exception {
      final var user = ProfileControllerIT.this.createUser(uniqueUsername("delexp"), UserRole.USER);
      final var token = ProfileControllerIT.this.expiredBearerTokenFor(user);

      ProfileControllerIT.this
          .mockMvc
          .perform(delete("/api/profile").with(apiPort()).header(AUTHORIZATION, token))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.msgId").value("sessionExpired"));

      assertThat(ProfileControllerIT.this.userRepository.findById(user.getId())).isPresent();
    }

    @Test
    @DisplayName("returns 404 when the user from the token no longer exists")
    void userNotFound() throws Exception {
      final var token = ProfileControllerIT.this.bearerTokenFor(UUID.randomUUID(), "ghost");

      ProfileControllerIT.this
          .mockMvc
          .perform(delete("/api/profile").with(apiPort()).header(AUTHORIZATION, token))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.msgId").value("userNotFound"));
    }
  }
}
