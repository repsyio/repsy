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
package io.repsy.os.server.protocols.cargo.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.auth0.jwt.JWT;
import com.jayway.jsonpath.JsonPath;
import io.repsy.os.server.shared.auth.AbstractPatIT;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.dtos.TokenScope;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RPS-1903: Cargo takes a personal access token as the raw {@code Authorization} value, which is
 * what the {@code cargo:token} credential provider sends, and as the Basic password. {@code /me}
 * answers the token with the JWT of the personal access token, which is then sent as the raw value
 * like any other. Every acceptance is paired with the same request refused, so a route that stopped
 * authenticating does not pass.
 */
@DisplayName("Cargo with a personal access token")
class CargoPatAuthIT extends AbstractPatIT {

  private static final String CRATE = "/{repo}/so/me/some-crate";
  private static final String ME = "/{repo}/me";

  private User owner;
  private Repo repo;

  @BeforeEach
  void setUp() {
    this.owner = this.createUser(uniqueUsername("cargopat"), UserRole.USER);
    this.repo = this.privateRepo(RepoType.CARGO);
  }

  private int crate(final String authorization) throws Exception {
    return this.status(get(CRATE, this.repo.getName()).header(AUTHORIZATION, authorization));
  }

  @Test
  @DisplayName("takes the raw secret in Authorization, with no scheme and no username")
  void rawSecret() throws Exception {
    final var pat = this.seedPat(this.owner, TokenScope.REPO_READ);

    assertThat(this.status(get(CRATE, this.repo.getName()))).isEqualTo(401);
    assertThat(this.crate(pat.secret())).isEqualTo(404);
    assertThat(this.crate(TokenFactory.personalAccessToken())).isEqualTo(401);
    assertThat(this.crate("Bearer " + pat.secret())).isEqualTo(404);
    assertThat(this.crate(basicAuth("typed", pat.secret()))).isEqualTo(404);
  }

  @Test
  @DisplayName("/me with Basic credentials answers the JWT of the token, whatever name is typed")
  void meWithBasic() throws Exception {
    final var pat = this.seedPat(this.owner, TokenScope.REPO_READ);
    final var response =
        this.protocol(
            get(ME, this.repo.getName()).header(AUTHORIZATION, basicAuth("typed", pat.secret())));

    assertThat(response.getStatus()).isEqualTo(200);

    final String jwt = JsonPath.read(response.getContentAsString(), "$.token");

    assertThat(response.getContentAsString()).doesNotContain(pat.secret());
    assertThat(JWT.decode(jwt).getAudience()).containsExactly("protocol");
    assertThat(JWT.decode(jwt).getSubject()).isEqualTo(pat.id().toString());
    // The JWT is then sent as the raw Authorization value, as `cargo` does.
    assertThat(this.crate(jwt)).isEqualTo(404);
  }

  @Test
  @DisplayName("/me with the raw secret answers the JWT of the token too")
  void meWithTheRawSecret() throws Exception {
    final var pat = this.seedPat(this.owner, TokenScope.REPO_READ);
    final var response =
        this.protocol(get(ME, this.repo.getName()).header(AUTHORIZATION, pat.secret()));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(this.crate(JsonPath.read(response.getContentAsString(), "$.token"))).isEqualTo(404);
  }

  @Test
  @DisplayName("/me refuses an unknown or expired secret and answers no token")
  void meRefusesAWrongSecret() throws Exception {
    final var pat = this.seedPat(this.owner, TokenScope.REPO_READ);

    for (final var authorization :
        new String[] {
          TokenFactory.personalAccessToken(), basicAuth("typed", TokenFactory.personalAccessToken())
        }) {
      assertThat(this.status(get(ME, this.repo.getName()).header(AUTHORIZATION, authorization)))
          .isEqualTo(401);
    }

    this.expire(pat);

    assertThat(this.status(get(ME, this.repo.getName()).header(AUTHORIZATION, pat.secret())))
        .isEqualTo(401);
  }

  @Test
  @DisplayName("the JWT of a token cannot be renewed into a login of its owner")
  void theJwtIsNotRenewedAsAUser() throws Exception {
    final var pat = this.seedPat(this.owner, TokenScope.REPO_READ);
    final var response =
        this.protocol(get(ME, this.repo.getName()).header(AUTHORIZATION, pat.secret()));
    final String jwt = JsonPath.read(response.getContentAsString(), "$.token");

    assertThat(this.status(get(ME, this.repo.getName()).header(AUTHORIZATION, jwt))).isEqualTo(401);
  }

  @Test
  @DisplayName("a revoked token and its JWT stop working at once")
  void endsWithTheToken() throws Exception {
    final var pat = this.seedPat(this.owner, TokenScope.REPO_READ);
    final var response =
        this.protocol(get(ME, this.repo.getName()).header(AUTHORIZATION, pat.secret()));
    final String jwt = JsonPath.read(response.getContentAsString(), "$.token");

    assertThat(this.crate(jwt)).isEqualTo(404);

    this.revoke(pat);

    assertThat(this.crate(jwt)).isEqualTo(401);
    assertThat(this.crate(pat.secret())).isEqualTo(401);
  }
}
