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
package io.repsy.os.server.protocols.docker.protocol.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.shared.token.entities.RepoDeployToken;
import io.repsy.os.server.shared.token.repositories.RepoDeployTokenRepository;
import io.repsy.os.server.shared.token.utils.DeployTokenHash;
import io.repsy.os.server.shared.token.utils.TokenUsernameGenerator;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * RPS-1220: {@code /v2/token} must validate an OAuth2 password-grant form body ({@code
 * grant_type=password}, as {@code oras-go}'s {@code ForceAttemptOAuth2} path sends -- which is what
 * {@code helm registry login} uses, since Helm has no token endpoint of its own and this path
 * parser matches every repo type) the same way it validates a Basic {@code Authorization} header.
 * Before the fix, the handler branched only on the header's presence, so a password grant with a
 * WRONG password still received a 200 anonymous token.
 */
@DisplayName("Docker OAuth2 password-grant token exchange")
class DockerOauthTokenIT extends AbstractIntegrationTest {

  private static final String IMAGE = "app";

  @Autowired private RepoDeployTokenRepository deployTokenRepository;

  private Repo privateRepo() {
    return this.seedRepo(RepoType.DOCKER, uniqueRepoName("priv"), true, null);
  }

  private Repo publicRepo() {
    return this.seedRepo(RepoType.DOCKER, uniqueRepoName("pub"), false, null);
  }

  /** Pulls a manifest that does not exist: 404 means the caller got past authentication. */
  private int pullManifest(final Repo repo, final String authorization) throws Exception {
    return this.mockMvc
        .perform(
            get("/v2/{repo}/{image}/manifests/latest", repo.getName(), IMAGE)
                .header(AUTHORIZATION, authorization)
                .with(protocolPort()))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  /** Inserts a deploy token for the repo and returns its secret. */
  private String seedDeployToken(final Repo repo) {
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

    return secret;
  }

  @Test
  @DisplayName("a password grant with a wrong password is 401 with a WWW-Authenticate challenge")
  void wrongPasswordIsRefused() throws Exception {
    final var repo = this.privateRepo();
    final var user = this.createUser(uniqueUsername("bob"), UserRole.USER);

    final var response =
        this.mockMvc
            .perform(
                post("/v2/token")
                    .param("grant_type", "password")
                    .param("username", user.getUsername())
                    .param("password", "definitely-wrong")
                    .param("scope", "repository:%s/%s:pull".formatted(repo.getName(), IMAGE))
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeader(WWW_AUTHENTICATE)).startsWith("Basic realm=");
    assertThat(response.getContentAsString()).doesNotContain("token");
  }

  @Test
  @DisplayName(
      "a password grant with the correct password returns a real user token, not an anonymous"
          + " one")
  void correctPasswordReturnsAUserToken() throws Exception {
    final var repo = this.privateRepo();
    final var user = this.createUser(uniqueUsername("bob"), UserRole.ADMIN);

    final var response =
        this.mockMvc
            .perform(
                post("/v2/token")
                    .param("grant_type", "password")
                    .param("username", user.getUsername())
                    .param("password", VALID_PASSWORD)
                    .param("scope", "repository:%s/%s:pull".formatted(repo.getName(), IMAGE))
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(200);
    final var token =
        AuthUtils.AUTH_BEARER + JsonPath.<String>read(response.getContentAsString(), "$.token");

    // An anonymous token is refused outright (401) on a private repo (DockerAnonymousTokenIT);
    // a real user's token gets past authentication and answers 404 for the missing manifest.
    assertThat(this.pullManifest(repo, token)).isEqualTo(404);
  }

  @Test
  @DisplayName(
      "a valid deploy token in the password field authenticates through the deploy-token" + " path")
  void deployTokenAsPasswordAuthenticates() throws Exception {
    final var repo = this.privateRepo();
    final var secret = this.seedDeployToken(repo);

    final var response =
        this.mockMvc
            .perform(
                post("/v2/token")
                    .param("grant_type", "password")
                    .param("username", "ignored-for-deploy-tokens")
                    .param("password", secret)
                    .param("scope", "repository:%s/%s:pull".formatted(repo.getName(), IMAGE))
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    final var token =
        AuthUtils.AUTH_BEARER + JsonPath.<String>read(response.getContentAsString(), "$.token");

    assertThat(this.pullManifest(repo, token)).isEqualTo(404);
  }

  @Test
  @DisplayName("a plain GET with no body on a public repo is unchanged: still 200 anonymous")
  void noBodyStillAnonymousOnPublicRepo() throws Exception {
    final var repo = this.publicRepo();

    final var response =
        this.mockMvc
            .perform(
                get("/v2/token")
                    .param("scope", "repository:%s/%s:pull".formatted(repo.getName(), IMAGE))
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(200);
  }
}
