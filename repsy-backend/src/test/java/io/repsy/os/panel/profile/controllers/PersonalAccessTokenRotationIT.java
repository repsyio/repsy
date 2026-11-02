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
import static org.springframework.http.HttpHeaders.CACHE_CONTROL;
import static org.springframework.http.HttpHeaders.LOCATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.server.shared.auth.AbstractPatIT;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.dtos.PersonalAccessTokenKind;
import io.repsy.os.shared.token.dtos.TokenScope;
import io.repsy.os.shared.token.entities.PersonalAccessToken;
import io.repsy.os.shared.token.utils.TokenHash;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-2429: {@code POST /api/profile/access-tokens/{tokenId}/actions/rotate}, on the real stack. A
 * rotated token has a new secret and a new id; the old secret, and anything that was issued for it,
 * ends with the request. The scopes and the name carry over, and so does the expiry unless it has
 * passed. The caller must be a login or a temporary token whose scopes cover those of the token.
 */
@DisplayName("Rotating a personal access token")
class PersonalAccessTokenRotationIT extends AbstractPatIT {

  private static final String TOKENS = "/api/profile/access-tokens";
  private static final String SECRET_PATTERN = "rut-[A-Za-z0-9_-]{43}";
  private static final String READ = "/{repo}/com/example/lib/1.0/lib-1.0.pom";

  @Autowired private ObjectMapper objectMapper;

  private User alice;
  private User bob;
  private String aliceLogin;
  private Repo mavenRepo;

  @BeforeEach
  void setUp() {
    this.alice = this.createUser(uniqueUsername("alice"), UserRole.ADMIN);
    this.bob = this.createUser(uniqueUsername("bob"), UserRole.USER);
    this.aliceLogin = this.bearerTokenFor(this.alice);
    this.mavenRepo = this.privateRepo(RepoType.MAVEN);
  }

  private static String bearer(final Pat pat) {
    return "Bearer " + pat.secret();
  }

  private MockHttpServletResponse rotate(final String authorization, final Object tokenId)
      throws Exception {
    return this.perform(
            post(TOKENS + "/" + tokenId + "/actions/rotate").header(AUTHORIZATION, authorization))
        .andReturn()
        .getResponse();
  }

  private PersonalAccessToken row(final UUID id) {
    this.entityManager.flush();
    this.entityManager.clear();

    return this.patRepository.findById(id).orElse(null);
  }

  private int protocolRead(final String secret) throws Exception {
    return this.status(
        get(READ, this.mavenRepo.getName()).header(AUTHORIZATION, basicAuth("any", secret)));
  }

  private int panelRead(final String secret) throws Exception {
    return this.perform(
            get("/api/repos/{repo}", this.mavenRepo.getName())
                .header(AUTHORIZATION, "Bearer " + secret))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private static UUID idOf(final MockHttpServletResponse response) throws Exception {
    return UUID.fromString(JsonPath.read(response.getContentAsString(), "$.id"));
  }

  private static String secretOf(final MockHttpServletResponse response) throws Exception {
    return JsonPath.read(response.getContentAsString(), "$.token");
  }

  private void seedLive(final User owner, final int count) {
    for (var i = 0; i < count; i++) {
      this.seedPat(owner, TokenScope.REPO_READ);
    }
  }

  @Test
  @DisplayName("a login gets the new secret once, no-store, and the new id in Location")
  void answersTheNewSecretAndTheNewId() throws Exception {
    final var old = this.seedPat(this.alice, TokenScope.REPO_READ);

    final var response = this.rotate(this.aliceLogin, old.id());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader(CACHE_CONTROL)).contains("no-store");
    assertThat(secretOf(response)).matches(SECRET_PATTERN).isNotEqualTo(old.secret());
    assertThat(idOf(response)).isNotEqualTo(old.id());
    assertThat(response.getHeader(LOCATION)).isEqualTo(TOKENS + "/" + idOf(response));
    assertThat(JsonPath.<Map<String, Object>>read(response.getContentAsString(), "$").keySet())
        .containsExactlyInAnyOrder("id", "name", "scopes", "expirationDate", "createdAt", "token");
  }

  @Test
  @DisplayName("the old row is gone and the new one stores the hash of the new secret only")
  void replacesTheRow() throws Exception {
    final var old = this.seedPat(this.alice, TokenScope.REPO_READ);

    final var response = this.rotate(this.aliceLogin, old.id());

    assertThat(this.row(old.id())).isNull();
    final var created = this.row(idOf(response));
    assertThat(created.getTokenHash()).isEqualTo(TokenHash.hash(secretOf(response)));
    assertThat(created.getTokenHash()).doesNotContain(secretOf(response));
    assertThat(created.getKind()).isEqualTo(PersonalAccessTokenKind.PERSONAL);
    assertThat(created.getUser().getId()).isEqualTo(this.alice.getId());
    assertThat(created.getLastUsedAt()).isNull();
  }

  @Test
  @DisplayName("the old secret stops working on the next request and the new one works")
  void theOldSecretIsDeadAndTheNewOneWorks() throws Exception {
    final var old = this.seedPat(this.alice, TokenScope.REPO_READ);

    assertThat(this.protocolRead(old.secret())).isEqualTo(404);
    assertThat(this.panelRead(old.secret())).isEqualTo(200);

    final var secret = secretOf(this.rotate(this.aliceLogin, old.id()));

    assertThat(this.protocolRead(old.secret())).isEqualTo(401);
    assertThat(this.panelRead(old.secret())).isEqualTo(401);
    assertThat(this.protocolRead(secret)).isEqualTo(404);
    assertThat(this.panelRead(secret)).isEqualTo(200);
  }

  @Test
  @DisplayName("a token that npm issued for the old secret stops working too")
  void aTokenIssuedForTheOldSecretEnds() throws Exception {
    final var npmRepo = this.privateRepo(RepoType.NPM);
    final var old =
        this.seedPat(
            this.alice, TokenScope.REPO_MANAGE, TokenScope.REPO_WRITE, TokenScope.REPO_READ);
    final var login =
        this.protocol(
            put("/{repo}/-/user/org.couchdb.user:{name}", npmRepo.getName(), "typed")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    this.objectMapper.writeValueAsBytes(
                        Map.of("name", "typed", "password", old.secret()))));
    assertThat(login.getStatus()).isEqualTo(201);
    final String jwt = JsonPath.read(login.getContentAsString(), "$.token");
    final var read =
        get("/{repo}/not-there", npmRepo.getName()).header(AUTHORIZATION, "Bearer " + jwt);

    assertThat(this.status(read)).isNotEqualTo(401);

    assertThat(this.rotate(this.aliceLogin, old.id()).getStatus()).isEqualTo(200);

    assertThat(this.status(read)).isEqualTo(401);
  }

  @Test
  @DisplayName("name and scopes carry over, none is added, and repo:manage is never implied")
  void carriesNameAndScopes() throws Exception {
    final var old = this.seedPat(this.alice, TokenScope.REPO_READ, TokenScope.REPO_WRITE);
    final var before = this.row(old.id());

    final var response = this.rotate(this.aliceLogin, old.id());

    final var created = this.row(idOf(response));
    assertThat(created.getName()).isEqualTo(before.getName());
    assertThat(created.getScopes()).isEqualTo(before.getScopes());
    assertThat(JsonPath.<java.util.List<String>>read(response.getContentAsString(), "$.scopes"))
        .doesNotContain("repo:manage");
  }

  @Test
  @DisplayName("the expiry carries over to the microsecond")
  void carriesTheExpiry() throws Exception {
    final var old =
        this.seedPat(this.alice, Instant.now().plus(Duration.ofDays(10)), TokenScope.REPO_READ);
    final var expiry = this.row(old.id()).getExpirationDate();

    final var response = this.rotate(this.aliceLogin, old.id());

    assertThat(this.row(idOf(response)).getExpirationDate()).isEqualTo(expiry);
  }

  @Test
  @DisplayName("a token that has expired comes back with 365 days from now")
  void anExpiredTokenStartsAgain() throws Exception {
    final var old =
        this.seedPat(this.alice, Instant.now().minus(Duration.ofDays(2)), TokenScope.REPO_READ);
    final var before = Instant.now();

    final var response = this.rotate(this.aliceLogin, old.id());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(this.row(idOf(response)).getExpirationDate())
        .isBetween(
            before.plus(Duration.ofDays(365)),
            Instant.now().plus(Duration.ofDays(365)).plusSeconds(1));
    assertThat(this.protocolRead(secretOf(response))).isEqualTo(404);
  }

  @Test
  @DisplayName("a user at 50 of 50 can rotate, and is still at 50")
  void rotatesAtTheLimit() throws Exception {
    final var old = this.seedPat(this.alice, TokenScope.REPO_READ);
    this.seedLive(this.alice, 49);

    final var response = this.rotate(this.aliceLogin, old.id());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(
            this.patRepository.countByUserIdAndKindAndExpirationDateAfter(
                this.alice.getId(), PersonalAccessTokenKind.PERSONAL, Instant.now()))
        .isEqualTo(50);
  }

  @Test
  @DisplayName("an expired token counts again once rotated, so at 50 of 50 it is refused")
  void anExpiredTokenNeedsRoom() throws Exception {
    final var old =
        this.seedPat(this.alice, Instant.now().minus(Duration.ofDays(2)), TokenScope.REPO_READ);
    this.seedLive(this.alice, 50);

    final var response = this.rotate(this.aliceLogin, old.id());

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).contains("accessTokenLimitReached");
    assertThat(this.row(old.id())).isNotNull();
  }

  @Test
  @DisplayName("a temporary token rotates a token whose scopes it has")
  void aTemporaryTokenRotatesWithinItsScopes() throws Exception {
    final var temporary =
        this.seedTemporary(this.alice, TokenScope.REPO_READ, TokenScope.REPO_WRITE);
    final var old = this.seedPat(this.alice, TokenScope.REPO_READ);

    final var response = this.rotate(bearer(temporary), old.id());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(this.row(old.id())).isNull();
    assertThat(this.row(idOf(response))).isNotNull();
  }

  @Test
  @DisplayName("a temporary token cannot rotate a token with more scopes than its own: 403")
  void aNarrowTokenCannotRotateABroadOne() throws Exception {
    final var temporary = this.seedTemporary(this.alice, TokenScope.REPO_READ);
    final var broad = this.seedPat(this.alice, TokenScope.REPO_READ, TokenScope.REPO_MANAGE);

    final var response = this.rotate(bearer(temporary), broad.id());

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("accessDenied").doesNotContain("rut-");
    assertThat(this.row(broad.id())).isNotNull();
    assertThat(this.protocolRead(broad.secret())).isEqualTo(404);
  }

  @Test
  @DisplayName(
      "a personal token with enough scopes rotates a token, and a narrow one cannot (RPS-2440)")
  void aPersonalTokenRotatesWithinItsScopes() throws Exception {
    final var personal =
        this.seedPat(
            this.alice, TokenScope.REPO_MANAGE, TokenScope.REPO_WRITE, TokenScope.REPO_READ);
    final var narrow = this.seedPat(this.alice, TokenScope.REPO_READ);
    final var victim = this.seedPat(this.alice, TokenScope.REPO_READ);
    final var broad = this.seedPat(this.alice, TokenScope.REPO_READ, TokenScope.REPO_MANAGE);

    assertThat(this.rotate(bearer(personal), victim.id()).getStatus()).isEqualTo(200);
    assertThat(this.row(victim.id())).isNull();

    // The token that rotates itself is rotated too, and its old secret is gone.
    assertThat(this.rotate(bearer(personal), personal.id()).getStatus()).isEqualTo(200);
    assertThat(this.row(personal.id())).isNull();

    // A narrow token cannot rotate a broader one: 403, and nothing changes.
    final var refused = this.rotate(bearer(narrow), broad.id());
    assertThat(refused.getStatus()).isEqualTo(403);
    assertThat(refused.getContentAsString()).contains("accessDenied").doesNotContain("rut-");
    assertThat(this.row(broad.id())).isNotNull();
  }

  @Test
  @DisplayName("a temporary token is not rotatable: 404, and it keeps working")
  void aTemporaryTokenIsNotRotatable() throws Exception {
    final var temporary = this.seedTemporary(this.alice, TokenScope.REPO_READ);

    assertThat(this.rotate(this.aliceLogin, temporary.id()).getStatus()).isEqualTo(404);

    assertThat(this.row(temporary.id())).isNotNull();
    assertThat(this.panelRead(temporary.secret())).isEqualTo(200);
  }

  @Test
  @DisplayName("a token of another user, and one that does not exist, are both 404")
  void anUnknownTokenIsNotFound() throws Exception {
    final var others = this.seedPat(this.bob, TokenScope.REPO_READ);

    final var other = this.rotate(this.aliceLogin, others.id());
    final var missing = this.rotate(this.aliceLogin, UUID.randomUUID());

    assertThat(other.getStatus()).isEqualTo(404);
    assertThat(missing.getStatus()).isEqualTo(404);
    assertThat(other.getContentAsString()).contains("accessTokenNotFound");
    assertThat(this.row(others.id())).isNotNull();
    assertThat(this.protocolRead(others.secret())).isEqualTo(404);
  }

  @Test
  @DisplayName("a bearer that is no credential at all is 401")
  void needsACredential() throws Exception {
    final var old = this.seedPat(this.alice, TokenScope.REPO_READ);

    assertThat(this.rotate("Bearer not-a-token", old.id()).getStatus()).isEqualTo(401);
    assertThat(this.row(old.id())).isNotNull();
  }
}
