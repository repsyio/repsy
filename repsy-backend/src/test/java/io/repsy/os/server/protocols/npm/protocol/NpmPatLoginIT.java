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
package io.repsy.os.server.protocols.npm.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.auth0.jwt.JWT;
import com.jayway.jsonpath.JsonPath;
import io.repsy.os.server.shared.auth.AbstractPatIntegrationTest;
import io.repsy.os.shared.auth.dtos.AuthenticationType;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.dtos.TokenScope;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1903: {@code npm login} with a personal access token as the password answers with a token the
 * npm endpoints accept, and the secret and its hash never come back. Like for a deploy token
 * (RPS-1045), every test logs in through the real {@code PUT /-/user/org.couchdb.user:{name}} flow
 * and uses the answer as the {@code _authToken} an npm client sends.
 *
 * <p>What npm stores is the JWT of the token, not a login of the user: it is read again with the
 * token on every request, so it carries the scopes of the token, ends with it, and is a protocol
 * token that the panel does not take.
 */
@DisplayName("npm login with a personal access token")
class NpmPatLoginIT extends AbstractPatIntegrationTest {

  private static final String PACKUMENT = "/{repo}/login-package";
  private static final String LOGIN = "/{repo}/-/user/org.couchdb.user:{name}";
  private static final String WHOAMI = "/{repo}/-/whoami";
  private static final String TOKEN = "/{repo}/-/user/token/{token}";
  private static final String TYPED_NAME = "whatever-the-client-typed";

  @Autowired private ObjectMapper objectMapper;

  private User owner;
  private Repo repo;

  @BeforeEach
  void setUp() {
    this.owner = this.createUser(uniqueUsername("npmpat"), UserRole.USER);
    this.repo = this.privateRepo(RepoType.NPM);
  }

  private static String bearer(final String token) {
    return AuthUtils.AUTH_BEARER + token;
  }

  private MockHttpServletResponse login(final String name, final String password) throws Exception {
    return this.protocol(
        put(LOGIN, this.repo.getName(), name)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                this.objectMapper.writeValueAsBytes(Map.of("name", name, "password", password))));
  }

  /** The token an npm client stores after {@code npm login} with the secret. */
  private String loginToken(final String secret) throws Exception {
    final var response = this.login(TYPED_NAME, secret);

    assertThat(response.getStatus()).isEqualTo(201);

    return JsonPath.read(response.getContentAsString(), "$.token");
  }

  private int read(final String authorization) throws Exception {
    return this.status(get(PACKUMENT, this.repo.getName()).header(AUTHORIZATION, authorization));
  }

  @Test
  @DisplayName("answers a token that works as the _authToken, whatever name was typed")
  void theLoginTokenWorks() throws Exception {
    final var pat = this.seedPat(this.owner, TokenScope.REPO_READ);
    final var token = this.loginToken(pat.secret());

    assertThat(this.read(bearer(token))).isEqualTo(404);
    assertThat(this.read(bearer(token + "x"))).isEqualTo(401);
    assertThat(this.status(get(PACKUMENT, this.repo.getName()))).isEqualTo(401);
  }

  @Test
  @DisplayName("never answers the secret or its hash, and issues a protocol token of the token")
  void theLoginTokenIsTheTokensJwt() throws Exception {
    final var pat = this.seedPat(this.owner, TokenScope.REPO_READ);
    final var stored = this.patRepository.findById(pat.id()).orElseThrow();
    final var response = this.login(TYPED_NAME, pat.secret());
    final var body = response.getContentAsString();
    final var jwt = JWT.decode(JsonPath.read(body, "$.token"));

    assertThat(body).doesNotContain(pat.secret()).doesNotContain(stored.getTokenHash());
    assertThat(jwt.getAudience()).containsExactly("protocol");
    assertThat(jwt.getSubject()).isEqualTo(pat.id().toString());
    assertThat(jwt.getClaim("authentication_type").asString()).isEqualTo("personal_access_token");
    assertThat(AuthenticationType.from("personal_access_token"))
        .isEqualTo(AuthenticationType.PERSONAL_ACCESS_TOKEN);
  }

  @Test
  @DisplayName("the login token does not outlive the personal access token")
  void theLoginTokenEndsWithTheToken() throws Exception {
    final var expires = Instant.now().plus(Duration.ofDays(2));
    final var pat = this.seedPat(this.owner, expires, TokenScope.REPO_READ);
    final var jwt = JWT.decode(this.loginToken(pat.secret()));

    assertThat(jwt.getExpiresAtAsInstant()).isBeforeOrEqualTo(expires.plusSeconds(1));
  }

  @Test
  @DisplayName("the secret itself is accepted as the Bearer value and as the Basic password")
  void theSecretIsAccepted() throws Exception {
    final var pat = this.seedPat(this.owner, TokenScope.REPO_READ);

    assertThat(this.read(bearer(pat.secret()))).isEqualTo(404);
    assertThat(this.read(basicAuth(TYPED_NAME, pat.secret()))).isEqualTo(404);
    assertThat(this.read(basicAuth(TYPED_NAME, TokenFactory.personalAccessToken()))).isEqualTo(401);
  }

  @Test
  @DisplayName("a login with an unknown or expired secret is refused and answers no token")
  void refusesAWrongSecret() throws Exception {
    final var pat = this.seedPat(this.owner, TokenScope.REPO_READ);

    assertThat(this.login(TYPED_NAME, TokenFactory.personalAccessToken()).getStatus())
        .isEqualTo(401);

    this.expire(pat);

    assertThat(this.login(TYPED_NAME, pat.secret()).getStatus()).isEqualTo(401);
  }

  @Test
  @DisplayName("whoami answers the owner of the token, whichever way it is presented")
  void whoamiAnswersTheOwner() throws Exception {
    final var pat = this.seedPat(this.owner, TokenScope.REPO_READ);
    final var token = this.loginToken(pat.secret());

    for (final var authorization :
        new String[] {bearer(token), bearer(pat.secret()), basicAuth("typed", pat.secret())}) {
      final var response =
          this.protocol(get(WHOAMI, this.repo.getName()).header(AUTHORIZATION, authorization));

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(response.getContentAsString()).contains(this.owner.getUsername());
    }
  }

  @Test
  @DisplayName("the login token carries the scopes of the token: a read token does not publish")
  void theLoginTokenKeepsTheScopes() throws Exception {
    final var pat = this.seedPat(this.owner, TokenScope.REPO_READ);
    final var token = this.loginToken(pat.secret());

    assertThat(
            this.status(
                put(PACKUMENT, this.repo.getName())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
                    .header(AUTHORIZATION, bearer(token))))
        .isEqualTo(401);
  }

  @Test
  @DisplayName("revoking or expiring the token ends the login token and the secret at once")
  void endsWithTheToken() throws Exception {
    final var pat = this.seedPat(this.owner, TokenScope.REPO_READ);
    final var token = this.loginToken(pat.secret());

    assertThat(this.read(bearer(token))).isEqualTo(404);

    this.expire(pat);

    assertThat(this.read(bearer(token))).isEqualTo(401);
    assertThat(this.read(bearer(pat.secret()))).isEqualTo(401);

    final var other = this.seedPat(this.owner, TokenScope.REPO_READ);
    final var otherToken = this.loginToken(other.secret());

    assertThat(this.read(bearer(otherToken))).isEqualTo(404);

    this.revoke(other);

    assertThat(this.read(bearer(otherToken))).isEqualTo(401);
  }

  @Test
  @DisplayName("npm logout with the login token of a personal access token revokes that JWT only")
  void logoutRevokesTheJwtOnly() throws Exception {
    final var pat = this.seedPat(this.owner, TokenScope.REPO_READ);
    final var token = this.loginToken(pat.secret());

    assertThat(
            this.status(
                delete(TOKEN, this.repo.getName(), token).header(AUTHORIZATION, bearer(token))))
        .isEqualTo(200);

    assertThat(this.read(bearer(token))).isEqualTo(401);
    // The token itself is managed in the panel: logout does not take it away.
    assertThat(this.read(bearer(pat.secret()))).isEqualTo(404);
  }
}
