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
package io.repsy.os.panel.connector.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.server.shared.auth.AbstractPatIT;
import io.repsy.os.shared.connector.repositories.ConnectorAuthorizationCodeRepository;
import io.repsy.os.shared.connector.utils.PkceUtils;
import io.repsy.os.shared.token.dtos.PersonalAccessTokenKind;
import io.repsy.os.shared.token.dtos.TokenScope;
import io.repsy.os.shared.token.entities.PersonalAccessToken;
import io.repsy.os.shared.token.utils.TokenHash;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * RPS-2660: the consent flow of a connected application and its {@code rat-} token, on the real
 * stack with the flow turned on: the code, its exchange with PKCE, where the token is accepted and
 * where it is refused, the listing, the revocations and the sliding expiry. The flow turned off is
 * {@code ConnectorDisabledIT}.
 */
// The same two properties as PagedListsIT, so the two classes share one context.
@TestPropertySource(
    properties = {
      "CONNECTOR_ENABLED=true",
      "CONNECTOR_REDIRECT_URIS=https://gateway.example.test/oauth/upstream/callback"
    })
@DisplayName("Connected applications (RPS-2660)")
class ConnectorTokenIT extends AbstractPatIT {

  private static final String REDIRECT = "https://gateway.example.test/oauth/upstream/callback";

  private static final String AUTHORIZATIONS = "/api/profile/connector-authorizations";
  private static final String EXCHANGE = "/api/connector/tokens";
  private static final String CONNECTOR_TOKENS = "/api/profile/connector-tokens";
  private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
  private static final String MAVEN_READ = "/{repo}/com/example/lib/1.0/lib-1.0.pom";
  private static final String CODE_PATH = "$.code";
  private static final String TOKEN_PATH = "$.token";
  private static final String INVALID_CODE = "invalidConnectorCode";

  @Autowired private ConnectorAuthorizationCodeRepository codeRepository;

  private User alice;
  private String aliceLogin;

  @BeforeEach
  void setUp() {
    this.alice = this.createUser(uniqueUsername("alice"), UserRole.ADMIN);
    this.aliceLogin = this.bearerTokenFor(this.alice);
  }

  private MockHttpServletResponse send(final MockHttpServletRequestBuilder request)
      throws Exception {
    return this.perform(request).andReturn().getResponse();
  }

  private static MockHttpServletRequestBuilder json(
      final MockHttpServletRequestBuilder request, final String body) {
    return request.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private static String consent(final String redirectUri, final String scopes) {
    return ("{\"redirectUri\":\"%s\",\"clientName\":\"Claude\",\"scopes\":[%s],"
            + "\"codeChallenge\":\"%s\",\"codeChallengeMethod\":\"S256\"}")
        .formatted(redirectUri, scopes, PkceUtils.challengeOf(VERIFIER));
  }

  private static String exchangeBody(
      final String code, final String verifier, final String redirectUri) {
    return "{\"code\":\"%s\",\"codeVerifier\":\"%s\",\"redirectUri\":\"%s\"}"
        .formatted(code, verifier, redirectUri);
  }

  private MockHttpServletResponse authorize(final String login, final String body)
      throws Exception {
    return this.send(json(post(AUTHORIZATIONS).header(AUTHORIZATION, login), body));
  }

  private String code() throws Exception {
    final var response = this.authorize(this.aliceLogin, consent(REDIRECT, "\"repo:read\""));

    assertThat(response.getStatus()).isEqualTo(201);

    return JsonPath.read(response.getContentAsString(), CODE_PATH);
  }

  private MockHttpServletResponse exchange(final String code, final String verifier)
      throws Exception {
    return this.send(json(post(EXCHANGE), exchangeBody(code, verifier, REDIRECT)));
  }

  /** A connector token of alice, through the whole flow. */
  private String connectorToken() throws Exception {
    final var response = this.exchange(this.code(), VERIFIER);

    assertThat(response.getStatus()).isEqualTo(201);

    return JsonPath.read(response.getContentAsString(), TOKEN_PATH);
  }

  private static String bearer(final String secret) {
    return "Bearer " + secret;
  }

  private static String errorCode(final MockHttpServletResponse response) throws Exception {
    return JsonPath.read(response.getContentAsString(), "$.code");
  }

  private PersonalAccessToken row(final String secret) {
    this.entityManager.flush();
    this.entityManager.clear();

    return this.patRepository.findByTokenHash(TokenHash.hash(secret)).orElseThrow();
  }

  // ---------------------------------------------------------------------------------------------
  // The consent and the exchange
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a consent answers a one-time code, no-store, and stores only its hash")
  void aConsentAnswersACode() throws Exception {
    final var response = this.authorize(this.aliceLogin, consent(REDIRECT, "\"repo:read\""));
    final String code = JsonPath.read(response.getContentAsString(), CODE_PATH);
    final String id = JsonPath.read(response.getContentAsString(), "$.id");

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(response.getHeader("Cache-Control")).contains("no-store");
    assertThat(response.getHeader("Location")).isEqualTo(AUTHORIZATIONS + "/" + id);
    assertThat(code).matches("[A-Za-z0-9_-]{43}");
    assertThat(this.codeRepository.findById(UUID.fromString(id)).orElseThrow().getCodeHash())
        .isEqualTo(TokenHash.hash(code));
  }

  @Test
  @DisplayName("the exchange answers a rat- token of the CONNECTOR kind, 90 days, no-store")
  void theExchangeAnswersAConnectorToken() throws Exception {
    final var before = Instant.now();
    final var response = this.exchange(this.code(), VERIFIER);
    final var body = response.getContentAsString();
    final String secret = JsonPath.read(body, TOKEN_PATH);
    final String id = JsonPath.read(body, "$.id");

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(response.getHeader("Cache-Control")).contains("no-store");
    assertThat(response.getHeader("Location")).isEqualTo(CONNECTOR_TOKENS + "/" + id);
    assertThat(secret).matches("rat-[A-Za-z0-9_-]{43}");
    assertThat(JsonPath.<List<String>>read(body, "$.scopes"))
        .containsExactly("profile:read", "repo:read");

    final var row = this.row(secret);
    assertThat(row.getKind()).isEqualTo(PersonalAccessTokenKind.CONNECTOR);
    assertThat(row.getName()).isEqualTo("Claude");
    assertThat(row.getUser().getId()).isEqualTo(this.alice.getId());
    assertThat(row.getExpirationDate())
        .isBetween(
            before.plus(Duration.ofDays(90)).minusSeconds(1),
            Instant.now().plus(Duration.ofDays(90)).plusSeconds(1));
  }

  @Test
  @DisplayName("a code works once, and a wrong verifier uses it up too")
  void aCodeWorksOnce() throws Exception {
    final var code = this.code();
    final var used = this.code();

    assertThat(this.exchange(code, VERIFIER).getStatus()).isEqualTo(201);
    final var again = this.exchange(code, VERIFIER);
    assertThat(again.getStatus()).isEqualTo(400);
    assertThat(errorCode(again)).isEqualTo(INVALID_CODE);

    final var wrong = this.exchange(used, VERIFIER.replace('d', 'e'));
    final var thenRight = this.exchange(used, VERIFIER);
    assertThat(errorCode(wrong)).isEqualTo(INVALID_CODE);
    assertThat(thenRight.getStatus()).isEqualTo(400);
    assertThat(errorCode(thenRight)).isEqualTo(INVALID_CODE);
  }

  @Test
  @DisplayName("an expired code, another redirect and an unknown code are the same answer")
  void mismatchesAreOneAnswer() throws Exception {
    final var expired = this.code();
    final var row = this.codeRepository.findByCodeHash(TokenHash.hash(expired)).orElseThrow();
    row.setExpiresAt(Instant.now().minusSeconds(1));
    this.codeRepository.saveAndFlush(row);
    final var otherRedirect =
        this.send(json(post(EXCHANGE), exchangeBody(this.code(), VERIFIER, REDIRECT + "/other")));

    assertThat(errorCode(this.exchange(expired, VERIFIER))).isEqualTo(INVALID_CODE);
    assertThat(errorCode(otherRedirect)).isEqualTo(INVALID_CODE);
    assertThat(errorCode(this.exchange("no-such-code", VERIFIER))).isEqualTo(INVALID_CODE);
  }

  @Test
  @DisplayName("failed exchanges count against the client like wrong passwords, then 429")
  void failedExchangesAreThrottled() throws Exception {
    var status = 400;

    for (int i = 0; i < 100 && status == 400; i++) {
      status = this.exchange("guess-" + i, VERIFIER).getStatus();
    }

    assertThat(status).isEqualTo(429);
  }

  @Test
  @DisplayName("a consent needs a login, a registered redirect, repo scopes and an S256 challenge")
  void consentRefusals() throws Exception {
    final var pat = this.seedPat(this.alice, TokenScope.REPO_MANAGE);
    final var unregistered =
        this.authorize(this.aliceLogin, consent(REDIRECT + "/x", "\"repo:read\""));
    final var scanRead = this.authorize(this.aliceLogin, consent(REDIRECT, "\"scan:read\""));
    final var plain =
        this.authorize(
            this.aliceLogin, consent(REDIRECT, "\"repo:read\"").replace("\"S256\"", "\"plain\""));

    assertThat(this.authorize(bearer(pat.secret()), consent(REDIRECT, "\"repo:read\"")).getStatus())
        .isEqualTo(401);
    assertThat(
            this.authorize(bearer(this.connectorToken()), consent(REDIRECT, "\"repo:read\""))
                .getStatus())
        .isEqualTo(401);
    assertThat(unregistered.getStatus()).isEqualTo(400);
    assertThat(errorCode(unregistered)).isEqualTo("connectorRedirectUriNotRegistered");
    assertThat(errorCode(scanRead)).isEqualTo("validationError");
    assertThat(errorCode(plain)).isEqualTo("validationError");
  }

  // ---------------------------------------------------------------------------------------------
  // Where the token is accepted and where it is refused
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("the token reads the panel API and says who it is")
  void theTokenReadsThePanelApi() throws Exception {
    final var repo = this.privateRepo(RepoType.MAVEN);
    final var secret = this.connectorToken();

    final var whoAmI =
        this.send(get("/api/profile/access-tokens/current").header(AUTHORIZATION, bearer(secret)));

    assertThat(whoAmI.getStatus()).isEqualTo(200);
    assertThat(JsonPath.<String>read(whoAmI.getContentAsString(), "$.name")).isEqualTo("Claude");
    assertThat(this.send(get("/api/repos").header(AUTHORIZATION, bearer(secret))).getStatus())
        .isEqualTo(200);
    assertThat(
            this.send(
                    get("/api/repos/{repo}", repo.getName()).header(AUTHORIZATION, bearer(secret)))
                .getStatus())
        .isEqualTo(200);
    assertThat(
            this.send(
                    delete("/api/repos/{repo}", repo.getName())
                        .header(AUTHORIZATION, bearer(secret)))
                .getStatus())
        .isEqualTo(403);
  }

  @Test
  @DisplayName("the token cannot manage tokens: 403 on the personal tokens, 401 on the logins only")
  void theTokenManagesNoToken() throws Exception {
    final var secret = this.connectorToken();
    final var pat = this.seedPat(this.alice, TokenScope.REPO_READ);
    final var create =
        json(
            post("/api/profile/access-tokens").header(AUTHORIZATION, bearer(secret)),
            "{\"name\":\"ci\",\"scopes\":[\"repo:read\"]}");

    assertThat(this.send(create).getStatus()).isEqualTo(403);
    assertThat(
            this.send(get("/api/profile/access-tokens").header(AUTHORIZATION, bearer(secret)))
                .getStatus())
        .isEqualTo(403);
    assertThat(
            this.send(
                    delete("/api/profile/access-tokens/{id}", pat.id())
                        .header(AUTHORIZATION, bearer(secret)))
                .getStatus())
        .isEqualTo(403);
    assertThat(
            this.send(
                    json(
                        post("/api/profile/cli-tokens").header(AUTHORIZATION, bearer(secret)),
                        "{\"scopes\":[\"repo:read\"]}"))
                .getStatus())
        .isEqualTo(401);
    assertThat(this.send(get(CONNECTOR_TOKENS).header(AUTHORIZATION, bearer(secret))).getStatus())
        .isEqualTo(401);
    assertThat(this.patRepository.findById(pat.id())).isPresent();
  }

  @Test
  @DisplayName("the token is refused on the protocol routes: Basic, Bearer and the Docker exchange")
  void theTokenIsNoProtocolCredential() throws Exception {
    final var maven = this.privateRepo(RepoType.MAVEN);
    final var npm = this.privateRepo(RepoType.NPM);
    final var docker = this.privateRepo(RepoType.DOCKER);
    final var secret = this.connectorToken();
    final var scope = "repository:%s/some-image:pull".formatted(docker.getName());

    assertThat(
            this.status(
                get(MAVEN_READ, maven.getName()).header(AUTHORIZATION, basicAuth("x", secret))))
        .isEqualTo(401);
    assertThat(
            this.status(
                get(MAVEN_READ, maven.getName())
                    .header(AUTHORIZATION, basicAuth(this.alice.getUsername(), secret))))
        .isEqualTo(401);
    assertThat(
            this.status(
                get("/{repo}/some-package", npm.getName()).header(AUTHORIZATION, bearer(secret))))
        .isEqualTo(401);
    assertThat(
            this.status(
                get("/v2/token")
                    .param("scope", scope)
                    .header(AUTHORIZATION, basicAuth("x", secret))))
        .isEqualTo(401);
  }

  // ---------------------------------------------------------------------------------------------
  // Listing and revoking
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("the connected applications are listed apart from the personal tokens")
  void theListing() throws Exception {
    this.seedPat(this.alice, TokenScope.REPO_READ);
    final var secret = this.connectorToken();
    final var expired = this.connectorToken();
    final var expiredRow = this.row(expired);
    expiredRow.setExpirationDate(Instant.now().minusSeconds(1));
    this.patRepository.saveAndFlush(expiredRow);

    final var connectors =
        this.send(get(CONNECTOR_TOKENS).header(AUTHORIZATION, this.aliceLogin))
            .getContentAsString();
    final var personal =
        this.send(get("/api/profile/access-tokens").header(AUTHORIZATION, this.aliceLogin))
            .getContentAsString();

    assertThat(JsonPath.<List<String>>read(connectors, "$.content[*].id"))
        .containsExactly(this.row(secret).getId().toString());
    assertThat(JsonPath.<List<String>>read(connectors, "$.content[*].name"))
        .containsExactly("Claude");
    assertThat(JsonPath.<Integer>read(personal, "$.page.totalElements")).isEqualTo(1);
    assertThat(
            this.send(
                    get(CONNECTOR_TOKENS)
                        .param("sort", "tokenHash")
                        .header(AUTHORIZATION, this.aliceLogin))
                .getStatus())
        .isEqualTo(400);
  }

  @Test
  @DisplayName("revoking in the panel ends the token at once; another user's is a 404")
  void revokingInThePanel() throws Exception {
    final var secret = this.connectorToken();
    final var id = this.row(secret).getId();
    final var bob = this.bearerTokenFor(this.createUser(uniqueUsername("bob"), UserRole.USER));
    final var pat = this.seedPat(this.alice, TokenScope.REPO_READ);

    assertThat(
            this.send(delete(CONNECTOR_TOKENS + "/{id}", id).header(AUTHORIZATION, bob))
                .getStatus())
        .isEqualTo(404);
    assertThat(
            this.send(
                    delete(CONNECTOR_TOKENS + "/{id}", pat.id())
                        .header(AUTHORIZATION, this.aliceLogin))
                .getStatus())
        .isEqualTo(404);
    assertThat(
            this.send(delete(CONNECTOR_TOKENS + "/{id}", id).header(AUTHORIZATION, this.aliceLogin))
                .getStatus())
        .isEqualTo(204);
    assertThat(this.send(get("/api/repos").header(AUTHORIZATION, bearer(secret))).getStatus())
        .isEqualTo(401);
  }

  @Test
  @DisplayName("the application revokes its own token; a login or a personal token cannot")
  void revokingItsOwnToken() throws Exception {
    final var secret = this.connectorToken();
    final var pat = this.seedPat(this.alice, TokenScope.REPO_READ);
    final var current = CONNECTOR_TOKENS + "/current";

    final var login = this.send(delete(current).header(AUTHORIZATION, this.aliceLogin));
    final var personal = this.send(delete(current).header(AUTHORIZATION, bearer(pat.secret())));

    assertThat(login.getStatus()).isEqualTo(400);
    assertThat(errorCode(login)).isEqualTo("notAConnectorToken");
    assertThat(errorCode(personal)).isEqualTo("notAConnectorToken");
    assertThat(this.send(delete(current).header(AUTHORIZATION, bearer(secret))).getStatus())
        .isEqualTo(204);
    assertThat(this.send(delete(current).header(AUTHORIZATION, bearer(secret))).getStatus())
        .isEqualTo(401);
  }

  // ---------------------------------------------------------------------------------------------
  // The sliding expiry
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a use moves the expiry to 90 days ahead, never past 365 days from the creation")
  void theSlidingExpiry() throws Exception {
    final var renewed = this.connectorToken();
    final var capped = this.connectorToken();
    final var renewedRow = this.row(renewed);
    renewedRow.setExpirationDate(Instant.now().plus(Duration.ofDays(10)));
    this.patRepository.saveAndFlush(renewedRow);
    final var cappedRow = this.row(capped);
    final var createdAt = Instant.now().minus(Duration.ofDays(360));
    cappedRow.setCreatedAt(createdAt);
    cappedRow.setExpirationDate(Instant.now().plus(Duration.ofDays(1)));
    this.patRepository.saveAndFlush(cappedRow);

    this.send(get("/api/repos").header(AUTHORIZATION, bearer(renewed)));
    this.send(get("/api/repos").header(AUTHORIZATION, bearer(capped)));

    assertThat(this.row(renewed).getExpirationDate())
        .isAfter(Instant.now().plus(Duration.ofDays(89)));
    assertThat(this.row(capped).getExpirationDate())
        .isBetween(
            createdAt.plus(Duration.ofDays(365)).minusSeconds(1),
            createdAt.plus(Duration.ofDays(365)).plusSeconds(1));
  }
}
