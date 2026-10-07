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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.dtos.TokenScope;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * RPS-1903: what ends a personal access token. A revoked, an expired and a deleted-with-its-user
 * token stops working on the very next request, on a protocol route and on a panel route, because
 * the row is read on every request. A password change, a username change and an admin edit do not
 * end it: it is not bound to the {@code token_version} of the user, like a deploy token and unlike
 * a login (the decision of RPS-1898). Each case is paired with the request working before.
 */
@DisplayName("A personal access token ends when it is revoked, expires or its user is deleted")
class PatRevocationIT extends AbstractPatIntegrationTest {

  private static final String READ = "/{repo}/com/example/lib/1.0/lib-1.0.pom";

  private User owner;
  private Repo repo;

  @BeforeEach
  void setUp() {
    this.owner = this.createUser(uniqueUsername("revoke"), UserRole.USER);
    this.repo = this.privateRepo(RepoType.MAVEN);
  }

  private int protocolRead(final Pat pat) throws Exception {
    return this.status(
        get(READ, this.repo.getName()).header(AUTHORIZATION, basicAuth("any", pat.secret())));
  }

  private int panelRead(final Pat pat) throws Exception {
    return this.mockMvc
        .perform(
            get("/api/repos/{repo}", this.repo.getName())
                .with(apiPort())
                .header(AUTHORIZATION, "Bearer " + pat.secret()))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Test
  @DisplayName("revoking it in the panel ends it at once, on protocol and panel routes")
  void revokedInThePanel() throws Exception {
    final var pat = this.seedPat(this.owner, TokenScope.REPO_READ);

    assertThat(this.protocolRead(pat)).isEqualTo(404);
    assertThat(this.panelRead(pat)).isEqualTo(200);

    this.mockMvc
        .perform(
            delete("/api/profile/access-tokens/{id}", pat.id())
                .with(apiPort())
                .header(AUTHORIZATION, this.bearerTokenFor(this.owner)))
        .andExpect(MockMvcResultMatchers.status().isNoContent());

    assertThat(this.protocolRead(pat)).isEqualTo(401);
    assertThat(this.panelRead(pat)).isEqualTo(401);
  }

  @Test
  @DisplayName("expiring ends it at once")
  void expired() throws Exception {
    final var pat = this.seedPat(this.owner, TokenScope.REPO_READ);

    assertThat(this.protocolRead(pat)).isEqualTo(404);

    this.expire(pat);

    assertThat(this.protocolRead(pat)).isEqualTo(401);
    assertThat(this.panelRead(pat)).isEqualTo(401);
  }

  @Test
  @DisplayName("deleting the user ends their tokens")
  void userDeleted() throws Exception {
    final var pat = this.seedPat(this.owner, TokenScope.REPO_READ);

    assertThat(this.protocolRead(pat)).isEqualTo(404);

    this.userTxService.deleteUserById(this.owner.getId());
    this.entityManager.flush();
    this.entityManager.clear();

    assertThat(this.protocolRead(pat)).isEqualTo(401);
    assertThat(this.panelRead(pat)).isEqualTo(401);
  }

  @Test
  @DisplayName("revoking one token leaves the others of the user working")
  void revokesOnlyThatToken() throws Exception {
    final var first = this.seedPat(this.owner, TokenScope.REPO_READ);
    final var second = this.seedPat(this.owner, TokenScope.REPO_READ);

    this.revoke(first);

    assertThat(this.protocolRead(first)).isEqualTo(401);
    assertThat(this.protocolRead(second)).isEqualTo(404);
  }

  @Test
  @DisplayName("a password change does not end it, while it ends the login of the user")
  void passwordChangeKeepsIt() throws Exception {
    final var pat = this.seedPat(this.owner, TokenScope.REPO_READ);
    final var login = this.bearerTokenFor(this.owner);

    assertThat(this.protocolRead(pat)).isEqualTo(404);

    this.mockMvc
        .perform(
            patch("/api/profile/password")
                .with(apiPort())
                .header(AUTHORIZATION, login)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"NewPassword2@\"}"))
        .andExpect(MockMvcResultMatchers.status().isOk());
    this.entityManager.flush();
    this.entityManager.clear();

    // The login token of the user is over (RPS-1604), the personal access token is not.
    this.mockMvc
        .perform(get("/api/profile").with(apiPort()).header(AUTHORIZATION, login))
        .andExpect(MockMvcResultMatchers.status().isUnauthorized());
    assertThat(this.protocolRead(pat)).isEqualTo(404);
    assertThat(this.panelRead(pat)).isEqualTo(200);
  }

  @Test
  @DisplayName("a username change does not end it, and it still names the owner")
  void usernameChangeKeepsIt() throws Exception {
    final var pat = this.seedPat(this.owner, TokenScope.REPO_READ);
    final var renamed = uniqueUsername("renamed");

    this.mockMvc
        .perform(
            patch("/api/profile/username")
                .with(apiPort())
                .header(AUTHORIZATION, this.bearerTokenFor(this.owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\"}".formatted(renamed)))
        .andExpect(MockMvcResultMatchers.status().isOk());
    this.entityManager.flush();
    this.entityManager.clear();

    assertThat(this.protocolRead(pat)).isEqualTo(404);
    this.mockMvc
        .perform(
            get("/api/profile/access-tokens/current")
                .with(apiPort())
                .header(AUTHORIZATION, "Bearer " + pat.secret()))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.username").value(renamed));
  }
}
