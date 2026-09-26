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
package io.repsy.os.server.shared.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.shared.token.entities.RepoDeployToken;
import io.repsy.os.server.shared.token.repositories.RepoDeployTokenRepository;
import io.repsy.os.server.shared.token.utils.DeployTokenHash;
import io.repsy.os.server.shared.token.utils.TokenUsernameGenerator;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.auth.utils.TokenRealm;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * RPS-1552: a protocol JWT a user logged in with (Docker {@code /v2/token}, the npm login token,
 * the Cargo {@code /me} token) is bound to the user's {@code token_version}, so a password change,
 * a username change or an admin's password reset ends it at once instead of when it expires (30
 * minutes, npm 90 days). A token minted before the claim existed is still accepted until it
 * expires, and a deploy-token JWT is not tied to the user at all.
 *
 * <p>Every test logs in through the real wire flow, changes the credentials through the real panel
 * endpoint, and probes with a read that answers 401 for a refused credential and something else
 * (404 for a package that does not exist) for an accepted one.
 */
@DisplayName("A protocol JWT ends when the token version of its user moves on")
class ProtocolJwtTokenVersionIT extends AbstractIntegrationTest {

  private static final String NEW_PASSWORD = "NewPassword2@";
  private static final String NPM_LOGIN = "/{repo}/-/user/org.couchdb.user:{name}";
  private static final String IMAGE = "some-image";

  @Autowired private ObjectMapper objectMapper;
  @Autowired private RepoDeployTokenRepository deployTokenRepository;

  static Stream<Arguments> protocols() {
    return Stream.of(
        Arguments.of(RepoType.DOCKER), Arguments.of(RepoType.NPM), Arguments.of(RepoType.CARGO));
  }

  private MockHttpServletResponse protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private Repo privateRepo(final RepoType type) {
    return this.seedRepo(type, uniqueRepoName("rps1552"), true, null);
  }

  /** What a package manager holds after logging in with {@code username} and {@code password}. */
  private String login(
      final RepoType type, final Repo repo, final String username, final String password)
      throws Exception {
    final var basic = basicAuth(username, password);
    final var response =
        switch (type) {
          case DOCKER ->
              this.protocol(
                  get("/v2/token")
                      .param("scope", "repository:%s/%s:pull".formatted(repo.getName(), IMAGE))
                      .header(AUTHORIZATION, basic));
          case NPM ->
              this.protocol(
                  put(NPM_LOGIN, repo.getName(), username)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          this.objectMapper.writeValueAsBytes(
                              Map.of("name", username, "password", password))));
          case CARGO ->
              this.protocol(get("/{repo}/me", repo.getName()).header(AUTHORIZATION, basic));
          default -> throw new IllegalArgumentException(type.name());
        };

    assertThat(response.getStatus()).as(response.getContentAsString()).isIn(200, 201);

    return JsonPath.read(response.getContentAsString(), "$.token");
  }

  /** A read with the JWT as a package manager sends it. */
  private MockHttpServletResponse read(final RepoType type, final Repo repo, final String jwt)
      throws Exception {
    final var path =
        switch (type) {
          case DOCKER -> "/v2/{repo}/" + IMAGE + "/manifests/latest";
          case NPM -> "/{repo}/some-package";
          case CARGO -> "/{repo}/so/me/some-crate";
          default -> throw new IllegalArgumentException(type.name());
        };

    return this.protocol(
        get(path, repo.getName()).header(AUTHORIZATION, AuthUtils.AUTH_BEARER + jwt));
  }

  private void assertAccepted(final RepoType type, final Repo repo, final String jwt)
      throws Exception {
    final var response = this.read(type, repo, jwt);

    assertThat(response.getStatus()).as(response.getContentAsString()).isNotEqualTo(401);
  }

  /**
   * A 401 that says the session is over. npm and Cargo answer Repsy's JSON error ({@code msgId});
   * Docker answers the registry error envelope, which carries the same text.
   */
  private void assertSessionExpired(final RepoType type, final Repo repo, final String jwt)
      throws Exception {
    final var response = this.read(type, repo, jwt);

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(401);
    assertThat(response.getContentAsString()).contains("Session expired");
  }

  private void changePassword(final User user, final String password) throws Exception {
    this.perform(
            put("/api/profile/password")
                .header(AUTHORIZATION, this.bearerTokenFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"%s\"}".formatted(password)))
        .andExpect(status().isOk());
  }

  @ParameterizedTest(name = "{0}: a login token works until the password is changed, then not")
  @MethodSource("protocols")
  void aPasswordChangeEndsTheLoginToken(final RepoType type) throws Exception {
    final var user = createUser(uniqueUsername("tvpw"), UserRole.USER);
    final var repo = privateRepo(type);
    final var jwt = login(type, repo, user.getUsername(), VALID_PASSWORD);

    assertAccepted(type, repo, jwt);
    assertAccepted(type, repo, jwt);

    changePassword(user, NEW_PASSWORD);

    assertSessionExpired(type, repo, jwt);

    // The new password logs in again and its token works.
    assertAccepted(type, repo, login(type, repo, user.getUsername(), NEW_PASSWORD));
  }

  @ParameterizedTest(name = "{0}: a login token stays valid when nothing about the user changed")
  @MethodSource("protocols")
  void aLoginTokenSurvivesAnUnrelatedChange(final RepoType type) throws Exception {
    final var user = createUser(uniqueUsername("tvsame"), UserRole.USER);
    final var other = createUser(uniqueUsername("tvother"), UserRole.USER);
    final var repo = privateRepo(type);
    final var jwt = login(type, repo, user.getUsername(), VALID_PASSWORD);

    // Another user's password change moves nobody else's version.
    changePassword(other, NEW_PASSWORD);

    assertAccepted(type, repo, jwt);
  }

  @ParameterizedTest(name = "{0}: a login token is refused once the user was renamed")
  @MethodSource("protocols")
  void aUsernameChangeEndsTheLoginToken(final RepoType type) throws Exception {
    final var user = createUser(uniqueUsername("tvold"), UserRole.USER);
    final var repo = privateRepo(type);
    final var jwt = login(type, repo, user.getUsername(), VALID_PASSWORD);

    this.perform(
            put("/api/profile/username")
                .header(AUTHORIZATION, this.bearerTokenFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\"}".formatted(uniqueUsername("tvnew"))))
        .andExpect(status().isOk());

    // The name in the token is gone, so the user is not found (the old mechanism): the answer is
    // the same 401, and never an accepted request.
    assertThat(this.read(type, repo, jwt).getStatus()).isEqualTo(401);
  }

  @ParameterizedTest(name = "{0}: an admin's password reset ends the login token")
  @MethodSource("protocols")
  void anAdminPasswordResetEndsTheLoginToken(final RepoType type) throws Exception {
    final var admin = createUser(uniqueUsername("tvadmin"), UserRole.ADMIN);
    final var user = createUser(uniqueUsername("tvreset"), UserRole.USER);
    final var repo = privateRepo(type);
    final var jwt = login(type, repo, user.getUsername(), VALID_PASSWORD);

    assertAccepted(type, repo, jwt);

    this.perform(
            post("/api/users/" + user.getId() + "/actions/reset-password")
                .header(AUTHORIZATION, this.bearerTokenFor(admin)))
        .andExpect(status().isOk());

    assertSessionExpired(type, repo, jwt);
  }

  @ParameterizedTest(name = "{0}: an admin edit that renames the user ends the login token")
  @MethodSource("protocols")
  void anAdminRenameEndsTheLoginToken(final RepoType type) throws Exception {
    final var admin = createUser(uniqueUsername("tvadm2"), UserRole.ADMIN);
    final var user = createUser(uniqueUsername("tvedit"), UserRole.USER);
    final var repo = privateRepo(type);
    final var jwt = login(type, repo, user.getUsername(), VALID_PASSWORD);

    this.perform(
            put("/api/users/" + user.getId())
                .header(AUTHORIZATION, this.bearerTokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"username\":\"%s\",\"role\":\"USER\"}".formatted(uniqueUsername("tvedn"))))
        .andExpect(status().isOk());

    assertThat(this.read(type, repo, jwt).getStatus()).isEqualTo(401);
  }

  @ParameterizedTest(name = "{0}: a token minted before the claim existed is accepted (grace)")
  @MethodSource("protocols")
  void aClaimlessTokenIsAcceptedUntilItExpires(final RepoType type) throws Exception {
    final var user = createUser(uniqueUsername("tvgrace"), UserRole.USER);
    final var repo = privateRepo(type);
    final var claimless = this.claimlessProtocolToken(user, Duration.ofMinutes(30));

    assertAccepted(type, repo, claimless);

    // A password change does not end it: it carries nothing to compare, which is the documented
    // one-release grace, bounded by the token's own expiry.
    changePassword(user, NEW_PASSWORD);

    assertAccepted(type, repo, claimless);

    final var expired = this.claimlessProtocolToken(user, Duration.ofSeconds(-60));
    final var response = this.read(type, repo, expired);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString()).contains("Session expired");
  }

  @Test
  @DisplayName("Cargo does not renew a login token that a password change ended")
  void cargoDoesNotRenewAnEndedToken() throws Exception {
    final var user = createUser(uniqueUsername("tvrenew"), UserRole.USER);
    final var repo = privateRepo(RepoType.CARGO);
    final var jwt = login(RepoType.CARGO, repo, user.getUsername(), VALID_PASSWORD);
    final var renewal = get("/{repo}/me", repo.getName());

    final var before = this.protocol(renewal.header(AUTHORIZATION, AuthUtils.AUTH_BEARER + jwt));
    assertThat(before.getStatus()).isEqualTo(200);

    changePassword(user, NEW_PASSWORD);

    final var after =
        this.protocol(
            get("/{repo}/me", repo.getName()).header(AUTHORIZATION, AuthUtils.AUTH_BEARER + jwt));
    assertThat(after.getStatus()).isEqualTo(401);
    assertThat(after.getContentAsString()).doesNotContain("\"token\"");
  }

  @Test
  @DisplayName("npm whoami and logout refuse a login token that a password change ended")
  void npmWhoamiRefusesAnEndedToken() throws Exception {
    final var user = createUser(uniqueUsername("tvwho"), UserRole.USER);
    final var repo = privateRepo(RepoType.NPM);
    final var jwt = login(RepoType.NPM, repo, user.getUsername(), VALID_PASSWORD);
    final var bearer = AuthUtils.AUTH_BEARER + jwt;

    assertThat(
            this.protocol(get("/{repo}/-/whoami", repo.getName()).header(AUTHORIZATION, bearer))
                .getStatus())
        .isEqualTo(200);

    changePassword(user, NEW_PASSWORD);

    assertThat(
            this.protocol(get("/{repo}/-/whoami", repo.getName()).header(AUTHORIZATION, bearer))
                .getStatus())
        .isEqualTo(401);
  }

  @Test
  @DisplayName("a refused token is not counted against the client like a wrong password")
  void aRefusedTokenIsNotCounted() throws Exception {
    final var user = createUser(uniqueUsername("tvthr"), UserRole.USER);
    final var repo = privateRepo(RepoType.NPM);
    final var jwt = login(RepoType.NPM, repo, user.getUsername(), VALID_PASSWORD);
    changePassword(user, NEW_PASSWORD);

    // More than the failures a client may make in a window, from an address of its own.
    for (var i = 0; i < 40; i++) {
      final var response =
          this.protocol(
              get("/{repo}/some-package", repo.getName())
                  .with(remoteAddr("203.0.113.52"))
                  .header(AUTHORIZATION, AuthUtils.AUTH_BEARER + jwt));

      assertThat(response.getStatus()).as("request " + i).isEqualTo(401);
      assertThat(JsonPath.<String>read(response.getContentAsString(), "$.msgId"))
          .isEqualTo("sessionExpired");
    }

    // The first wrong credential of that client is a plain 401, not the 429 of a blocked client.
    final var wrong =
        this.protocol(
            get("/{repo}/some-package", repo.getName())
                .with(remoteAddr("203.0.113.52"))
                .header(AUTHORIZATION, "Bearer not.a.token"));
    assertThat(wrong.getStatus()).isEqualTo(401);
  }

  @ParameterizedTest(name = "{0}: a deploy-token JWT is not tied to any user's version")
  @EnumSource(
      value = RepoType.class,
      names = {"DOCKER", "NPM", "CARGO"})
  void aDeployTokenJwtIsUnaffected(final RepoType type) throws Exception {
    final var user = createUser(uniqueUsername("tvdep"), UserRole.ADMIN);
    final var repo = privateRepo(type);
    final var secret = this.seedDeployToken(repo);
    final var jwt = this.login(type, repo, secret.username(), secret.secret());

    assertThat(JWT.decode(jwt).getClaim("tv").isMissing()).isTrue();
    assertAccepted(type, repo, jwt);

    changePassword(user, NEW_PASSWORD);

    assertAccepted(type, repo, jwt);
  }

  @Test
  @DisplayName("the tv claim is the version of the user at the login, and only user tokens have it")
  void theClaimIsTheVersionAtLogin() throws Exception {
    final var user = createUser(uniqueUsername("tvclaim"), UserRole.USER);
    final var repo = privateRepo(RepoType.DOCKER);

    final var first = login(RepoType.DOCKER, repo, user.getUsername(), VALID_PASSWORD);
    changePassword(user, NEW_PASSWORD);
    final var second = login(RepoType.DOCKER, repo, user.getUsername(), NEW_PASSWORD);

    final var versionNow =
        this.userRepository.findById(user.getId()).orElseThrow().getTokenVersion();

    assertThat(JWT.decode(first).getClaim("tv").asInt()).isEqualTo(versionNow - 1);
    assertThat(JWT.decode(second).getClaim("tv").asInt()).isEqualTo(versionNow);
  }

  /** A protocol token as the release before RPS-1552 minted it: no {@code tv} claim. */
  private String claimlessProtocolToken(final User user, final Duration ttl) {
    final var secret = (String) ReflectionTestUtils.getField(this.jwtUtils, "secret");

    return JWT.create()
        .withJWTId(UUID.randomUUID().toString())
        .withSubject(user.getId().toString())
        .withAudience(TokenRealm.PROTOCOL.getAudience())
        .withClaim("username", user.getUsername())
        .withExpiresAt(Instant.now().plus(ttl))
        .sign(Algorithm.HMAC512(secret));
  }

  private record DeployCredential(String username, String secret) {}

  private DeployCredential seedDeployToken(final Repo repo) {
    final var secret = TokenFactory.deployToken();
    final var entity = new RepoDeployToken();

    entity.setRepo(this.repoRepository.getReferenceById(repo.getId()));
    entity.setName("token-" + secret.substring(0, 6));
    entity.setUsername(TokenUsernameGenerator.deployTokenUsername());
    entity.setToken(DeployTokenHash.hash(secret));
    entity.setReadOnly(false);
    entity.setExpirationDate(Instant.now().plus(30, ChronoUnit.DAYS));
    entity.setTokenDurationDay(30);
    this.deployTokenRepository.save(entity);
    this.entityManager.flush();

    return new DeployCredential(entity.getUsername(), secret);
  }
}
