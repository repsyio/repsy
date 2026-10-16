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
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.server.shared.auth.AbstractPatIT;
import io.repsy.os.shared.token.dtos.PersonalAccessTokenKind;
import io.repsy.os.shared.token.dtos.TokenScope;
import io.repsy.os.shared.token.entities.PersonalAccessToken;
import io.repsy.os.shared.token.services.PersonalAccessTokenService;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.os.shared.token.utils.TokenHash;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.auth.PasswordHasher;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * RPS-2039: the temporary {@code rct-} token of a CLI login. It is a row of the personal access
 * token table of another kind, so it is recognized by the same entry points as a {@code rut-}
 * token; what differs is checked here, on the real stack: the 24-hour expiry, the exemption from
 * the cap, no listing, the replacement of the previous token, the {@code
 * /api/profile/access-tokens} route matrix (a temporary token manages personal tokens, a personal
 * token does not), and the {@code rct-} password rule on the protocol routes.
 */
@DisplayName("Temporary CLI tokens")
class TemporaryCliTokenIT extends AbstractPatIT {

  private static final String TOKENS = "/api/profile/access-tokens";
  private static final String CLI_TOKENS = "/api/profile/cli-tokens";
  private static final String MAVEN_READ = "/{repo}/com/example/lib/1.0/lib-1.0.pom";

  private static final String RCT_PASSWORD = "rct-this-is-my-password";
  private static final String ORDINARY_PASSWORD = "this-is-my-password";
  private static final String RCT_PASSWORD_HASH = PasswordHasher.hash(RCT_PASSWORD);
  private static final String ORDINARY_PASSWORD_HASH = PasswordHasher.hash(ORDINARY_PASSWORD);

  private User alice;
  private User bob;
  private String aliceLogin;

  @BeforeEach
  void setUp() {
    this.alice = this.createUser(uniqueUsername("alice"), UserRole.USER);
    this.bob = this.createUser(uniqueUsername("bob"), UserRole.USER);
    this.aliceLogin = this.bearerTokenFor(this.alice);
  }

  private static String bearer(final Pat pat) {
    return "Bearer " + pat.secret();
  }

  private ResultActions createCliToken(final String authorization, final String json)
      throws Exception {
    return this.perform(
        post(CLI_TOKENS)
            .header(AUTHORIZATION, authorization)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private ResultActions createPat(final String authorization, final String... scopes)
      throws Exception {
    final var quoted = java.util.stream.Stream.of(scopes).map(s -> "\"" + s + "\"").toList();

    return this.perform(
        post(TOKENS)
            .header(AUTHORIZATION, authorization)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"ci\",\"scopes\":[%s]}".formatted(String.join(",", quoted))));
  }

  private ResultActions listPats(final String authorization) throws Exception {
    return this.perform(get(TOKENS).header(AUTHORIZATION, authorization));
  }

  private ResultActions revokePat(final String authorization, final Object tokenId)
      throws Exception {
    return this.perform(delete(TOKENS + "/" + tokenId).header(AUTHORIZATION, authorization));
  }

  private ResultActions revokePrevious(final String authorization, final Object tokenId)
      throws Exception {
    return this.perform(
        post(CLI_TOKENS + "/" + tokenId + "/actions/revoke-previous")
            .header(AUTHORIZATION, authorization));
  }

  private static String cliForm(final String... scopes) {
    final var quoted = java.util.stream.Stream.of(scopes).map(s -> "\"" + s + "\"").toList();

    return "{\"scopes\":[%s]}".formatted(String.join(",", quoted));
  }

  private PersonalAccessToken row(final UUID id) {
    this.entityManager.flush();
    this.entityManager.clear();

    return this.patRepository.findById(id).orElse(null);
  }

  // ---------------------------------------------------------------------------------------------
  // Creating one
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("POST /cli-tokens with a login creates a rct- token that lives 24 hours, no-store")
  void createsATemporaryToken() throws Exception {
    final var before = Instant.now();
    final var result = this.createCliToken(this.aliceLogin, cliForm("repo:read", "repo:write"));
    final var response = result.andReturn().getResponse();
    final var body = response.getContentAsString();

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(response.getHeader("Cache-Control")).contains("no-store");
    final String secret = JsonPath.read(body, "$.token");
    final var id = UUID.fromString(JsonPath.read(body, "$.id"));
    assertThat(secret).matches("rct-[A-Za-z0-9_-]{43}");
    assertThat(response.getHeader("Location")).isEqualTo(CLI_TOKENS + "/" + id);

    final var stored = this.row(id);
    assertThat(stored.getKind()).isEqualTo(PersonalAccessTokenKind.TEMPORARY);
    assertThat(stored.getTokenHash()).isEqualTo(TokenHash.hash(secret));
    assertThat(stored.getExpirationDate())
        .isBetween(
            before.plus(Duration.ofHours(24)).minusSeconds(1),
            Instant.now().plus(Duration.ofHours(24)).plusSeconds(1));
    assertThat(JsonPath.<List<String>>read(body, "$.scopes"))
        .containsExactly("profile:read", "repo:read", "repo:write");
  }

  @Test
  @DisplayName("a temporary token cannot create a temporary token, and neither can a personal one")
  void onlyALoginCreatesATemporaryToken() throws Exception {
    final var temporary = this.seedTemporary(this.alice, TokenScope.REPO_MANAGE);
    final var personal = this.seedPat(this.alice, TokenScope.REPO_MANAGE);

    assertThat(
            this.createCliToken(bearer(temporary), cliForm("repo:read"))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(401);
    assertThat(
            this.createCliToken(bearer(personal), cliForm("repo:read"))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(401);
    assertThat(this.patRepository.countByUserId(this.alice.getId())).isEqualTo(2);
  }

  @Test
  @DisplayName("POST /cli-tokens refuses a form with no scope")
  void needsAScope() throws Exception {
    assertThat(
            this.createCliToken(this.aliceLogin, cliForm()).andReturn().getResponse().getStatus())
        .isEqualTo(400);
  }

  @Test
  @DisplayName("a temporary token does not count against the cap of 50 personal tokens")
  void doesNotCountAgainstTheCap() throws Exception {
    for (int i = 0; i < PersonalAccessTokenService.MAX_TOKENS_PER_USER; i++) {
      this.seedPat(this.alice, TokenScope.REPO_READ);
    }

    assertThat(this.createPat(this.aliceLogin, "repo:read").andReturn().getResponse().getStatus())
        .isEqualTo(400);
    assertThat(
            this.createCliToken(this.aliceLogin, cliForm("repo:read"))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(201);
  }

  @Test
  @DisplayName("fifty temporary tokens leave room for a personal token")
  void temporaryTokensTakeNoRoom() throws Exception {
    for (int i = 0; i < PersonalAccessTokenService.MAX_TOKENS_PER_USER; i++) {
      this.seedTemporary(this.alice, TokenScope.REPO_READ);
    }

    assertThat(this.createPat(this.aliceLogin, "repo:read").andReturn().getResponse().getStatus())
        .isEqualTo(201);
  }

  // ---------------------------------------------------------------------------------------------
  // Not listed, not revocable as a personal token
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a temporary token is not in the list of a login, nor of a temporary token")
  void isNotListed() throws Exception {
    final var temporary = this.seedTemporary(this.alice, TokenScope.REPO_READ);
    this.seedPat(this.alice, TokenScope.REPO_READ);

    for (final var authorization : List.of(this.aliceLogin, bearer(temporary))) {
      final var body = this.listPats(authorization).andReturn().getResponse().getContentAsString();

      assertThat(JsonPath.<Integer>read(body, "$.page.totalElements")).isEqualTo(1);
      assertThat(body).doesNotContain(temporary.id().toString());
    }
  }

  @Test
  @DisplayName("DELETE /access-tokens/{id} does not reach a temporary token, even the caller's own")
  void cannotRevokeATemporaryTokenAsAPersonalOne() throws Exception {
    final var temporary = this.seedTemporary(this.alice, TokenScope.REPO_READ);
    final var other = this.seedTemporary(this.alice, TokenScope.REPO_READ);

    assertThat(this.revokePat(this.aliceLogin, other.id()).andReturn().getResponse().getStatus())
        .isEqualTo(404);
    assertThat(
            this.revokePat(bearer(temporary), temporary.id()).andReturn().getResponse().getStatus())
        .isEqualTo(404);
    assertThat(this.row(temporary.id())).isNotNull();
    assertThat(this.row(other.id())).isNotNull();
  }

  // ---------------------------------------------------------------------------------------------
  // The route matrix of /api/profile/access-tokens
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a temporary token creates, lists and revokes personal tokens (rp token create)")
  void temporaryTokenManagesPersonalTokens() throws Exception {
    final var temporary =
        this.seedTemporary(this.alice, TokenScope.REPO_READ, TokenScope.REPO_WRITE);

    final var created = this.createPat(bearer(temporary), "repo:read").andReturn().getResponse();
    assertThat(created.getStatus()).isEqualTo(201);
    final String secret = JsonPath.read(created.getContentAsString(), "$.token");
    final var id = UUID.fromString(JsonPath.read(created.getContentAsString(), "$.id"));
    assertThat(secret).startsWith("rut-");
    assertThat(this.row(id).getKind()).isEqualTo(PersonalAccessTokenKind.PERSONAL);

    assertThat(this.listPats(bearer(temporary)).andReturn().getResponse().getContentAsString())
        .contains(id.toString());
    assertThat(this.revokePat(bearer(temporary), id).andReturn().getResponse().getStatus())
        .isEqualTo(204);
    assertThat(this.row(id)).isNull();
  }

  @Test
  @DisplayName("a personal token cannot create, list or revoke personal tokens: 401 on all three")
  void personalTokenManagesNothing() throws Exception {
    final var personal =
        this.seedPat(
            this.alice, TokenScope.REPO_MANAGE, TokenScope.REPO_WRITE, TokenScope.REPO_READ);
    final var victim = this.seedPat(this.alice, TokenScope.REPO_READ);

    assertThat(this.createPat(bearer(personal), "repo:read").andReturn().getResponse().getStatus())
        .isEqualTo(401);
    assertThat(this.listPats(bearer(personal)).andReturn().getResponse().getStatus())
        .isEqualTo(401);
    assertThat(this.revokePat(bearer(personal), victim.id()).andReturn().getResponse().getStatus())
        .isEqualTo(401);
    assertThat(this.row(victim.id())).isNotNull();
    assertThat(this.patRepository.countByUserId(this.alice.getId())).isEqualTo(2);
  }

  @Test
  @DisplayName("the login keeps working on all three routes")
  void loginStillManagesPersonalTokens() throws Exception {
    final var created = this.createPat(this.aliceLogin, "repo:read").andReturn().getResponse();
    final var id = JsonPath.<String>read(created.getContentAsString(), "$.id");

    assertThat(created.getStatus()).isEqualTo(201);
    assertThat(this.listPats(this.aliceLogin).andReturn().getResponse().getStatus()).isEqualTo(200);
    assertThat(this.revokePat(this.aliceLogin, id).andReturn().getResponse().getStatus())
        .isEqualTo(204);
  }

  @Test
  @DisplayName("a temporary token cannot hand on a scope it does not have: 400, nothing created")
  void temporaryTokenCannotWidenItself() throws Exception {
    final var temporary = this.seedTemporary(this.alice, TokenScope.REPO_READ);

    final var response =
        this.createPat(bearer(temporary), "repo:read", "repo:manage").andReturn().getResponse();

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).contains("validationError");
    assertThat(this.patRepository.countByUserId(this.alice.getId())).isEqualTo(1);
  }

  @Test
  @DisplayName("an expired or revoked temporary token is refused on the token routes")
  void expiredTemporaryTokenIsRefused() throws Exception {
    final var expired = this.seedTemporary(this.alice, TokenScope.REPO_READ);
    this.expire(expired);
    final var revoked = this.seedTemporary(this.alice, TokenScope.REPO_READ);
    this.revoke(revoked);

    assertThat(this.listPats(bearer(expired)).andReturn().getResponse().getStatus()).isEqualTo(401);
    assertThat(this.listPats(bearer(revoked)).andReturn().getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  @DisplayName("/current answers who a temporary token is, and a personal token too")
  void currentAnswersBothKinds() throws Exception {
    final var temporary = this.seedTemporary(this.alice, TokenScope.REPO_READ);
    final var personal = this.seedPat(this.alice, TokenScope.REPO_READ);

    for (final var pat : List.of(temporary, personal)) {
      final var response =
          this.perform(get(TOKENS + "/current").header(AUTHORIZATION, bearer(pat)))
              .andReturn()
              .getResponse();

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(JsonPath.<String>read(response.getContentAsString(), "$.username"))
          .isEqualTo(this.alice.getUsername());
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Replacing the previous temporary token
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "revoke-previous deletes the older temporary tokens of the user and keeps the new one")
  void revokesThePrevious() throws Exception {
    final var first = this.seedTemporary(this.alice, TokenScope.REPO_READ);
    final var created =
        this.createCliToken(this.aliceLogin, cliForm("repo:read")).andReturn().getResponse();
    final var newId = UUID.fromString(JsonPath.read(created.getContentAsString(), "$.id"));
    final var personal = this.seedPat(this.alice, TokenScope.REPO_READ);
    final var bobs = this.seedTemporary(this.bob, TokenScope.REPO_READ);

    assertThat(this.row(first.id())).isNotNull();
    assertThat(this.revokePrevious(this.aliceLogin, newId).andReturn().getResponse().getStatus())
        .isEqualTo(204);

    assertThat(this.row(first.id())).isNull();
    assertThat(this.row(newId)).isNotNull();
    assertThat(this.row(personal.id())).isNotNull();
    assertThat(this.row(bobs.id())).isNotNull();
    // Repeating it changes nothing.
    assertThat(this.revokePrevious(this.aliceLogin, newId).andReturn().getResponse().getStatus())
        .isEqualTo(204);
    assertThat(this.row(newId)).isNotNull();
  }

  @Test
  @DisplayName("the replaced token stops working at once, on a protocol route")
  void theReplacedTokenStopsWorking() throws Exception {
    final var repo = this.seedRepo(RepoType.MAVEN, uniqueRepoName("rps2039"), true, null);
    final var first = this.seedTemporary(this.alice, TokenScope.REPO_READ);
    final var created =
        this.createCliToken(this.aliceLogin, cliForm("repo:read")).andReturn().getResponse();
    final var newId = UUID.fromString(JsonPath.read(created.getContentAsString(), "$.id"));
    final String newSecret = JsonPath.read(created.getContentAsString(), "$.token");

    assertThat(
            this.status(
                get(MAVEN_READ, repo.getName())
                    .header(AUTHORIZATION, basicAuth("x", first.secret()))))
        .isEqualTo(404);

    this.revokePrevious(this.aliceLogin, newId);

    assertThat(
            this.status(
                get(MAVEN_READ, repo.getName())
                    .header(AUTHORIZATION, basicAuth("x", first.secret()))))
        .isEqualTo(401);
    assertThat(
            this.status(
                get(MAVEN_READ, repo.getName()).header(AUTHORIZATION, basicAuth("x", newSecret))))
        .isEqualTo(404);
  }

  @Test
  @DisplayName("a newer temporary token is never revoked by the confirmation of an older one")
  void aNewerTokenSurvives() throws Exception {
    final var older = this.seedTemporary(this.alice, TokenScope.REPO_READ);
    this.setCreatedAt(older, Instant.now().minusSeconds(60));
    final var newer = this.seedTemporary(this.alice, TokenScope.REPO_READ);

    assertThat(
            this.revokePrevious(this.aliceLogin, older.id()).andReturn().getResponse().getStatus())
        .isEqualTo(204);

    assertThat(this.row(newer.id())).isNotNull();
    assertThat(this.row(older.id())).isNotNull();
  }

  @Test
  @DisplayName(
      "revoke-previous is 404 for another user's token, a personal token and an unknown id")
  void revokePreviousNeedsOwnTemporaryToken() throws Exception {
    final var bobs = this.seedTemporary(this.bob, TokenScope.REPO_READ);
    final var personal = this.seedPat(this.alice, TokenScope.REPO_READ);

    for (final var id : List.of(bobs.id(), personal.id(), UUID.randomUUID())) {
      assertThat(this.revokePrevious(this.aliceLogin, id).andReturn().getResponse().getStatus())
          .isEqualTo(404);
    }

    assertThat(this.row(bobs.id())).isNotNull();
  }

  @Test
  @DisplayName("revoke-previous takes a login only: a temporary token and a personal token are 401")
  void revokePreviousIsLoginOnly() throws Exception {
    final var older = this.seedTemporary(this.alice, TokenScope.REPO_READ);
    this.setCreatedAt(older, Instant.now().minusSeconds(60));
    final var temporary = this.seedTemporary(this.alice, TokenScope.REPO_READ);
    final var personal = this.seedPat(this.alice, TokenScope.REPO_READ);

    for (final var pat : List.of(temporary, personal)) {
      assertThat(
              this.revokePrevious(bearer(pat), temporary.id())
                  .andReturn()
                  .getResponse()
                  .getStatus())
          .isEqualTo(401);
    }

    assertThat(this.row(older.id())).isNotNull();
  }

  private void setCreatedAt(final Pat pat, final Instant createdAt) {
    this.entityManager.flush();
    this.entityManager
        .createNativeQuery("update personal_access_token set created_at = ?1 where id = ?2")
        .setParameter(1, java.sql.Timestamp.from(createdAt))
        .setParameter(2, pat.id())
        .executeUpdate();
    this.entityManager.clear();
  }

  // ---------------------------------------------------------------------------------------------
  // The protocol entry points
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "a temporary token authenticates as the Basic password on Maven and npm, read only by scope")
  void protocolBasicPassword() throws Exception {
    final var maven = this.seedRepo(RepoType.MAVEN, uniqueRepoName("rps2039"), true, null);
    final var npm = this.seedRepo(RepoType.NPM, uniqueRepoName("rps2039"), true, null);
    final var reader = this.seedTemporary(this.alice, TokenScope.REPO_READ);
    final var none = this.seedTemporary(this.alice, TokenScope.SCAN_READ);

    assertThat(
            this.status(
                get(MAVEN_READ, maven.getName())
                    .header(AUTHORIZATION, basicAuth("x", reader.secret()))))
        .isEqualTo(404);
    assertThat(
            this.status(
                get("/{repo}/some-package", npm.getName())
                    .header(AUTHORIZATION, basicAuth("x", reader.secret()))))
        .isEqualTo(404);
    assertThat(
            this.status(
                get(MAVEN_READ, maven.getName())
                    .header(AUTHORIZATION, basicAuth("x", none.secret()))))
        .isEqualTo(401);
    assertThat(
            this.status(
                get(MAVEN_READ, maven.getName())
                    .header(AUTHORIZATION, basicAuth("x", TokenFactory.cliToken()))))
        .isEqualTo(401);
  }

  @Test
  @DisplayName("a temporary token is the password of the Docker token exchange")
  void dockerTokenExchange() throws Exception {
    final var docker = this.seedRepo(RepoType.DOCKER, uniqueRepoName("rps2039"), true, null);
    final var reader = this.seedTemporary(this.alice, TokenScope.REPO_READ);
    final var scope = "repository:%s/some-image:pull".formatted(docker.getName());

    final var ok =
        this.protocol(
            get("/v2/token")
                .param("scope", scope)
                .header(AUTHORIZATION, basicAuth("x", reader.secret())));
    final var bad =
        this.protocol(
            get("/v2/token")
                .param("scope", scope)
                .header(AUTHORIZATION, basicAuth("x", TokenFactory.cliToken())));

    assertThat(ok.getStatus()).isEqualTo(200);
    assertThat(JsonPath.<String>read(ok.getContentAsString(), "$.token")).isNotBlank();
    assertThat(bad.getStatus()).isEqualTo(401);
  }

  @Test
  @DisplayName("a user password that starts with rct- is refused as a password, like rut-")
  void rctPasswordIsRefused() throws Exception {
    final var maven = this.seedRepo(RepoType.MAVEN, uniqueRepoName("rps2039"), true, null);
    final var rctUser = uniqueUsername("rctpass");
    final var ordinaryUser = uniqueUsername("plainpass");

    this.userTxService.create(rctUser, UserRole.ADMIN, RCT_PASSWORD_HASH);
    this.userTxService.create(ordinaryUser, UserRole.ADMIN, ORDINARY_PASSWORD_HASH);

    // The control: the same setup with an ordinary password gets through to the (missing) file.
    assertThat(
            this.status(
                get(MAVEN_READ, maven.getName())
                    .header(AUTHORIZATION, basicAuth(ordinaryUser, ORDINARY_PASSWORD))))
        .isEqualTo(404);
    assertThat(
            this.status(
                get(MAVEN_READ, maven.getName())
                    .header(AUTHORIZATION, basicAuth(rctUser, RCT_PASSWORD))))
        .isEqualTo(401);
  }
}
