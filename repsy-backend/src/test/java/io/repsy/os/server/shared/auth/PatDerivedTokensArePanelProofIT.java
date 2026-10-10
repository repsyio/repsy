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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.auth0.jwt.JWT;
import com.jayway.jsonpath.JsonPath;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.shared.auth.PanelAuthHelper;
import io.repsy.os.shared.auth.utils.TokenRealm;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.dtos.TokenScope;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1903, the invariant: no token that comes from a personal access token is a panel token. What
 * a personal access token is exchanged for (the npm login token, Cargo's {@code /me} token;
 * Docker's is checked in {@code DockerPatTokenExchangeIT}) is a protocol JWT, never {@code
 * aud=panel}, and every place that verifies a panel token refuses it: the panel routes, {@code
 * PanelAuthHelper}, and the {@code JwtUtils} panel checks themselves. The token is made by the real
 * exchanges, owned by an admin with every scope, so a refusal cannot be put down to a missing
 * right.
 */
@DisplayName("A token made from a personal access token is never a panel token")
class PatDerivedTokensArePanelProofIT extends AbstractPatIT {

  @Autowired private PanelAuthHelper panelAuthHelper;
  @Autowired private ObjectMapper objectMapper;

  private User admin;
  private Repo npmRepo;
  private Repo cargoRepo;

  @BeforeEach
  void setUp() {
    this.admin = this.createUser(uniqueUsername("panelproof"), UserRole.ADMIN);
    this.npmRepo = this.privateRepo(RepoType.NPM);
    this.cargoRepo = this.privateRepo(RepoType.CARGO);
  }

  private Pat everything() {
    return this.seedPat(
        this.admin,
        TokenScope.REPO_MANAGE,
        TokenScope.REPO_WRITE,
        TokenScope.REPO_READ,
        TokenScope.SCAN_READ);
  }

  private String npmLoginToken(final Pat pat) throws Exception {
    final var response =
        this.protocol(
            put("/{repo}/-/user/org.couchdb.user:{name}", this.npmRepo.getName(), "typed")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    this.objectMapper.writeValueAsBytes(
                        Map.of("name", "typed", "password", pat.secret()))));

    assertThat(response.getStatus()).isEqualTo(201);

    return JsonPath.read(response.getContentAsString(), "$.token");
  }

  private String cargoToken(final Pat pat) throws Exception {
    final var response =
        this.protocol(
            get("/{repo}/me", this.cargoRepo.getName()).header(AUTHORIZATION, pat.secret()));

    assertThat(response.getStatus()).isEqualTo(200);

    return JsonPath.read(response.getContentAsString(), "$.token");
  }

  private List<String> derivedTokens() throws Exception {
    final var pat = this.everything();

    return List.of(this.npmLoginToken(pat), this.cargoToken(pat));
  }

  private int panel(final AbstractMockHttpServletRequestBuilder<?> request, final String jwt)
      throws Exception {
    return this.mockMvc
        .perform(request.with(apiPort()).header(AUTHORIZATION, "Bearer " + jwt))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Test
  @DisplayName("is made for the protocol realm and for it only")
  void theRealmIsProtocol() throws Exception {
    for (final var jwt : this.derivedTokens()) {
      assertThat(JWT.decode(jwt).getAudience()).containsExactly("protocol");
      assertThat(JWT.decode(jwt).getClaim("authentication_type").asString())
          .isEqualTo("personal_access_token");
      assertThat(JWT.decode(jwt).getClaim("session_start").isMissing()).isTrue();
    }
  }

  @Test
  @DisplayName("is refused by every panel check of JwtUtils and PanelAuthHelper")
  void everyPanelCheckRefusesIt() throws Exception {
    for (final var jwt : this.derivedTokens()) {
      final var header = "Bearer " + jwt;

      assertThatThrownBy(() -> this.jwtUtils.extractPanelClaims(header))
          .isInstanceOf(UnAuthorizedException.class);
      assertThatThrownBy(() -> this.jwtUtils.verify(header, TokenRealm.PANEL))
          .isInstanceOf(UnAuthorizedException.class);
      assertThatThrownBy(() -> this.jwtUtils.extractSessionStart(header))
          .isInstanceOf(UnAuthorizedException.class);
      assertThatThrownBy(() -> this.jwtUtils.extractTokenVersion(header))
          .isInstanceOf(UnAuthorizedException.class);
      assertThatThrownBy(() -> this.panelAuthHelper.authenticate(header))
          .isInstanceOf(UnAuthorizedException.class);
      assertThatThrownBy(() -> this.panelAuthHelper.authenticateSession(header))
          .isInstanceOf(UnAuthorizedException.class);
      assertThatThrownBy(() -> this.panelAuthHelper.authenticateRepoReader(header))
          .isInstanceOf(UnAuthorizedException.class);
      assertThatThrownBy(() -> this.panelAuthHelper.authenticateRepoCreator(header))
          .isInstanceOf(UnAuthorizedException.class);
      assertThatThrownBy(() -> this.panelAuthHelper.authenticateAccessToken(header))
          .isInstanceOf(UnAuthorizedException.class);
    }
  }

  @Test
  @DisplayName(
      "is refused on every panel route, the ones that take a token and the ones that do not")
  void everyPanelRouteRefusesIt() throws Exception {
    final var repo = this.npmRepo.getName();

    for (final var jwt : this.derivedTokens()) {
      assertThat(this.panel(get("/api/profile"), jwt)).isEqualTo(401);
      assertThat(this.panel(get("/api/repos"), jwt)).isEqualTo(401);
      assertThat(this.panel(get("/api/repos/counts"), jwt)).isEqualTo(401);
      assertThat(this.panel(get("/api/repos/{repo}", repo), jwt)).isEqualTo(401);
      assertThat(this.panel(get("/api/repos/{repo}/permissions", repo), jwt)).isEqualTo(401);
      assertThat(this.panel(get("/api/repos/{repo}/deploy-tokens", repo), jwt)).isEqualTo(401);
      assertThat(this.panel(get("/api/repos/no-such-repo"), jwt)).isEqualTo(401);
      assertThat(this.panel(get("/api/profile/access-tokens/current"), jwt)).isEqualTo(401);
      assertThat(this.panel(get("/api/profile/access-tokens"), jwt)).isEqualTo(401);
      assertThat(this.panel(get("/api/usage"), jwt)).isEqualTo(401);
      assertThat(this.panel(get("/api/users"), jwt)).isEqualTo(401);
      assertThat(this.panel(delete("/api/repos/{repo}", repo), jwt)).isEqualTo(401);
      assertThat(
              this.panel(
                  patch("/api/profile/password")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"password\":\"NewPassword2@\"}"),
                  jwt))
          .isEqualTo(401);
      assertThat(
              this.panel(
                  post("/api/repos")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"name\":\"nope\",\"type\":\"MAVEN\"}"),
                  jwt))
          .isEqualTo(401);
    }
  }
}
