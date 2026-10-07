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
package io.repsy.os.server.protocols.docker.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.auth0.jwt.JWT;
import com.jayway.jsonpath.JsonPath;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.server.shared.auth.AbstractPatIntegrationTest;
import io.repsy.os.server.shared.auth.AuthThrottleProperties;
import io.repsy.os.shared.auth.PanelAuthHelper;
import io.repsy.os.shared.auth.utils.TokenRealm;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.dtos.TokenScope;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * RPS-1903: the Docker token exchange takes a personal access token as the password, whatever the
 * username is, and answers a protocol JWT of the token. The JWT carries the grants that were asked
 * for, narrowed to the scopes of the token and the role of its owner, and every {@code /v2} request
 * reads the token again, so it ends with the token and the intersection is applied once more with
 * what the token is allowed now. A raw secret as the Bearer value of {@code /v2} is refused, as it
 * is for a deploy token. The JWT is never a panel token.
 *
 * <p>A request that reaches the registry for a resource that does not exist answers {@code 404} (or
 * {@code 202} for the start of an upload): it is {@code 401} where the token is refused.
 */
@DisplayName("Docker token exchange with a personal access token")
class DockerPatTokenExchangeIT extends AbstractPatIntegrationTest {

  private static final String IMAGE = "some-image";
  private static final String DIGEST =
      "sha256:0000000000000000000000000000000000000000000000000000000000000000";

  @Autowired private PanelAuthHelper panelAuthHelper;
  @Autowired private AuthThrottleProperties throttleProperties;

  private User user;
  private User admin;
  private Repo repo;

  @BeforeEach
  void setUp() {
    this.user = this.createUser(uniqueUsername("dockeruser"), UserRole.USER);
    this.admin = this.createUser(uniqueUsername("dockeradmin"), UserRole.ADMIN);
    this.repo = this.privateRepo(RepoType.DOCKER);
  }

  private String name() {
    return this.repo.getName() + "/" + IMAGE;
  }

  /** The token exchange every Docker client does, with the scope it will use (or none). */
  private MockHttpServletResponse exchange(
      final String username, final String secret, final String actions) throws Exception {
    final var request = get("/v2/token").header(AUTHORIZATION, basicAuth(username, secret));

    if (actions != null) {
      request.param("scope", "repository:%s:%s".formatted(this.name(), actions));
    }

    return this.protocol(request);
  }

  private String jwt(final MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).isEqualTo(200);

    return JsonPath.read(response.getContentAsString(), "$.token");
  }

  private int pull(final String bearer) throws Exception {
    return this.status(
        get("/v2/{repo}/{image}/manifests/latest", this.repo.getName(), IMAGE)
            .header(AUTHORIZATION, "Bearer " + bearer));
  }

  private int startPush(final String bearer) throws Exception {
    return this.status(
        post("/v2/{repo}/{image}/blobs/uploads/", this.repo.getName(), IMAGE)
            .header(AUTHORIZATION, "Bearer " + bearer));
  }

  private int deleteManifest(final String bearer) throws Exception {
    return this.status(
        delete("/v2/{repo}/{image}/manifests/{ref}", this.repo.getName(), IMAGE, DIGEST)
            .header(AUTHORIZATION, "Bearer " + bearer));
  }

  @ParameterizedTest(name = "username {0}")
  @CsvSource({"owner", "wrong", "empty", "another"})
  @DisplayName(
      "takes any username, as the Basic credentials of the exchange and as a password grant")
  void ignoresTheUsername(final String kind) throws Exception {
    final var pat = this.seedPat(this.user, TokenScope.REPO_READ);
    final var username =
        switch (kind) {
          case "owner" -> this.user.getUsername();
          case "wrong" -> "not-the-owner";
          case "empty" -> "";
          default -> this.admin.getUsername();
        };

    assertThat(this.pull(this.jwt(this.exchange(username, pat.secret(), "pull")))).isEqualTo(404);

    final var grant =
        this.protocol(
            post("/v2/token")
                .param("grant_type", "password")
                .param("username", username.isEmpty() ? "typed" : username)
                .param("password", pat.secret())
                .param("scope", "repository:%s:pull".formatted(this.name())));

    assertThat(this.pull(this.jwt(grant))).isEqualTo(404);
  }

  @Test
  @DisplayName("is a protocol token of the personal access token, never a panel one")
  void theJwtIsAProtocolTokenOfTheToken() throws Exception {
    final var pat = this.seedPat(this.admin, TokenScope.REPO_MANAGE);
    final var response = this.exchange("typed", pat.secret(), "pull,push");
    final var body = response.getContentAsString();
    final var decoded = JWT.decode(this.jwt(response));

    assertThat(body).doesNotContain(pat.secret());
    assertThat(decoded.getAudience()).containsExactly("protocol");
    assertThat(decoded.getSubject()).isEqualTo(pat.id().toString());
    assertThat(decoded.getClaim("authentication_type").asString())
        .isEqualTo("personal_access_token");
    assertThat(decoded.getClaim("username").asString()).isEqualTo(this.admin.getUsername());
    assertThat(decoded.getClaim("session_start").isMissing()).isTrue();
    assertThat(decoded.getClaim("tv").isMissing()).isTrue();
  }

  @ParameterizedTest(name = "{0} owner, {1}, asked {2}: {3}")
  @CsvSource(
      delimiter = '|',
      value = {
        "admin | REPO_READ   | pull,push,delete | pull",
        "user  | REPO_WRITE  | pull,push,delete | pull,push",
        "admin | REPO_WRITE  | *                | pull,push",
        "user  | REPO_MANAGE | *                | pull,push",
        "admin | REPO_MANAGE | *                | pull,push,delete",
        "admin | REPO_MANAGE | pull,push,delete | pull,push,delete"
      })
  @DisplayName("carries the grants that were asked for, narrowed to the scopes and the role")
  void theGrantsAreNarrowed(
      final String owner, final TokenScope scope, final String asked, final String expected)
      throws Exception {
    final var pat = this.seedPat("admin".equals(owner) ? this.admin : this.user, scope);
    final var decoded = JWT.decode(this.jwt(this.exchange("typed", pat.secret(), asked)));

    assertThat(decoded.getClaim("access").asList(String.class))
        .containsExactly(this.name() + ":" + expected);
  }

  @Test
  @DisplayName("is refused when nothing of what was asked is left, and that is not counted")
  void anEmptyIntersectionIsRefused() throws Exception {
    final var reader = this.seedPat(this.admin, TokenScope.REPO_READ);
    final var profileOnly = this.seedPat(this.admin);

    for (int i = 0; i < this.throttleProperties.maxFailures() + 3; i++) {
      assertThat(this.exchange("typed", reader.secret(), "push").getStatus()).isEqualTo(401);
      assertThat(this.exchange("typed", profileOnly.secret(), "pull").getStatus()).isEqualTo(401);
    }

    assertThat(this.exchange("typed", reader.secret(), "pull").getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName(
      "answers a docker login, which asks for nothing, even for a token with no repo scope")
  void dockerLogin() throws Exception {
    final var profileOnly = this.seedPat(this.user);
    final var response = this.exchange("typed", profileOnly.secret(), null);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(JWT.decode(this.jwt(response)).getClaim("access").asList(String.class)).isEmpty();
  }

  @Test
  @DisplayName("refuses an unknown or expired secret, and counts it")
  void unknownAndExpired() throws Exception {
    final var pat = this.seedPat(this.user, TokenScope.REPO_READ);

    assertThat(this.exchange("typed", TokenFactory.personalAccessToken(), "pull").getStatus())
        .isEqualTo(401);

    this.expire(pat);

    assertThat(this.exchange("typed", pat.secret(), "pull").getStatus()).isEqualTo(401);

    for (int i = 0; i < this.throttleProperties.maxFailures(); i++) {
      this.exchange("typed", TokenFactory.personalAccessToken(), "pull");
    }

    assertThat(this.exchange("typed", TokenFactory.personalAccessToken(), "pull").getStatus())
        .isEqualTo(429);
  }

  @Test
  @DisplayName("refuses the raw secret as the Bearer value of /v2, valid or not")
  void rawSecretOnV2() throws Exception {
    final var pat = this.seedPat(this.user, TokenScope.REPO_READ);

    assertThat(this.pull(pat.secret())).isEqualTo(401);
    assertThat(this.pull(TokenFactory.personalAccessToken())).isEqualTo(401);
  }

  @Test
  @DisplayName(
      "every /v2 request is a read of the token: scopes, revocation and expiry apply at once")
  void theTokenIsReadOnEveryRequest() throws Exception {
    final var pat = this.seedPat(this.user, TokenScope.REPO_WRITE);
    final var jwt = this.jwt(this.exchange("typed", pat.secret(), "pull,push"));

    assertThat(this.pull(jwt)).isEqualTo(404);
    assertThat(this.startPush(jwt)).isNotEqualTo(401);

    // The scopes it has now apply, not the ones it had when the JWT was made.
    final var row = this.patRepository.findById(pat.id()).orElseThrow();

    row.setScopes(TokenScope.withImplicit(List.of(TokenScope.REPO_READ)));
    this.patRepository.saveAndFlush(row);

    assertThat(this.pull(jwt)).isEqualTo(404);
    assertThat(this.startPush(jwt)).isEqualTo(401);

    this.revoke(pat);

    assertThat(this.pull(jwt)).isEqualTo(401);
  }

  @Test
  @DisplayName("the JWT does not outlive the token it was made from")
  void theJwtDoesNotOutliveTheToken() throws Exception {
    final var expires = Instant.now().plus(Duration.ofMinutes(5));
    final var pat = this.seedPat(this.user, expires, TokenScope.REPO_READ);
    final var decoded = JWT.decode(this.jwt(this.exchange("typed", pat.secret(), "pull")));

    assertThat(decoded.getExpiresAtAsInstant()).isBeforeOrEqualTo(expires.plusSeconds(1));
    // And it is the 30 minutes of every protocol token when the token lasts longer.
    final var longer = this.seedPat(this.user, TokenScope.REPO_READ);
    final var other = JWT.decode(this.jwt(this.exchange("typed", longer.secret(), "pull")));

    assertThat(other.getExpiresAtAsInstant()).isBefore(Instant.now().plus(Duration.ofMinutes(31)));
  }

  @Test
  @DisplayName("an expired token ends its JWT at once")
  void expiryEndsTheJwt() throws Exception {
    final var pat = this.seedPat(this.user, TokenScope.REPO_READ);
    final var jwt = this.jwt(this.exchange("typed", pat.secret(), "pull"));

    assertThat(this.pull(jwt)).isEqualTo(404);

    this.expire(pat);

    assertThat(this.pull(jwt)).isEqualTo(401);
  }

  @Test
  @DisplayName("a read token cannot push, whatever it asked for")
  void aReadTokenCannotPush() throws Exception {
    final var pat = this.seedPat(this.admin, TokenScope.REPO_READ);

    assertThat(this.exchange("typed", pat.secret(), "push").getStatus()).isEqualTo(401);

    final var jwt = this.jwt(this.exchange("typed", pat.secret(), "pull,push"));

    assertThat(this.pull(jwt)).isEqualTo(404);
    assertThat(this.startPush(jwt)).isEqualTo(401);
  }

  @ParameterizedTest(name = "{0} token of an admin asked for {1}: delete is {2}")
  @CsvSource(
      delimiter = '|',
      value = {
        "REPO_MANAGE | pull,push,delete | allowed",
        "REPO_MANAGE | pull,push        | refused",
        "REPO_WRITE  | pull,push,delete | refused"
      })
  @DisplayName("deleting a manifest needs repo:manage, an admin owner and the delete grant")
  void deleting(final TokenScope scope, final String asked, final String outcome) throws Exception {
    final var pat = this.seedPat(this.admin, scope);
    final var jwt = this.jwt(this.exchange("typed", pat.secret(), asked));

    if ("allowed".equals(outcome)) {
      assertThat(this.deleteManifest(jwt)).isNotEqualTo(401);
    } else {
      assertThat(this.deleteManifest(jwt)).isEqualTo(401);
    }
  }

  @Test
  @DisplayName("deleting needs an admin owner: a repo:manage token of a user is not enough")
  void manageNeedsAnAdmin() throws Exception {
    final var pat = this.seedPat(this.user, TokenScope.REPO_MANAGE);
    final var jwt = this.jwt(this.exchange("typed", pat.secret(), "pull,push,delete"));

    assertThat(this.deleteManifest(jwt)).isEqualTo(401);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = TokenRealm.class,
      names = {"PANEL"})
  @DisplayName("the JWT is refused by every panel check and every panel route")
  void neverAPanelToken(final TokenRealm realm) throws Exception {
    final var pat = this.seedPat(this.admin, TokenScope.REPO_MANAGE);
    final var jwt = this.jwt(this.exchange("typed", pat.secret(), "pull,push,delete"));
    final var header = "Bearer " + jwt;

    assertThat(JWT.decode(jwt).getAudience()).doesNotContain(realm.getAudience());
    assertThatThrownBy(() -> this.jwtUtils.verify(header, realm))
        .isInstanceOf(UnAuthorizedException.class);
    assertThatThrownBy(() -> this.jwtUtils.extractPanelClaims(header))
        .isInstanceOf(UnAuthorizedException.class);
    assertThatThrownBy(() -> this.panelAuthHelper.authenticate(header))
        .isInstanceOf(UnAuthorizedException.class);
    assertThatThrownBy(() -> this.panelAuthHelper.authenticateRepoReader(header))
        .isInstanceOf(UnAuthorizedException.class);
    assertThatThrownBy(() -> this.panelAuthHelper.authenticateAccessToken(header))
        .isInstanceOf(UnAuthorizedException.class);

    for (final var path :
        new String[] {
          "/api/profile", "/api/repos", "/api/repos/" + this.repo.getName(), "/api/usage"
        }) {
      assertThat(
              this.mockMvc
                  .perform(get(path).with(apiPort()).header(AUTHORIZATION, header))
                  .andReturn()
                  .getResponse()
                  .getStatus())
          .as(path)
          .isEqualTo(401);
    }
  }
}
