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
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.shared.token.entities.RepoDeployToken;
import io.repsy.os.server.shared.token.repositories.RepoDeployTokenRepository;
import io.repsy.os.server.shared.token.utils.DeployTokenHash;
import io.repsy.os.server.shared.token.utils.TokenUsernameGenerator;
import io.repsy.os.shared.auth.repositories.RevokedProtocolTokenRepository;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1361: {@code npm logout} and {@code pnpm logout} send {@code DELETE /-/user/token/<token>}.
 * It used to end in {@code 404 unknownPath}, so a logout failed and the token stayed valid.
 *
 * <p>Every test logs in through the real {@code PUT /-/user/org.couchdb.user:{name}} flow, so the
 * token is the one an npm client stores, and then shows on the wire that the token works before the
 * logout and is refused after it.
 */
@DisplayName("npm wire protocol DELETE /-/user/token/{token}")
class NpmTokenRevokeProtocolIT extends AbstractIntegrationTest {

  private static final String LOGIN = "/{repo}/-/user/org.couchdb.user:{name}";
  private static final String TOKEN = "/{repo}/-/user/token/{token}";
  private static final String WHOAMI = "/{repo}/-/whoami";
  private static final String PACKUMENT = "/{repo}/absent-package";

  @Autowired private ObjectMapper objectMapper;
  @Autowired private RepoDeployTokenRepository deployTokenRepository;
  @Autowired private RevokedProtocolTokenRepository revokedTokenRepository;

  private MvcResult protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn();
  }

  private int status(final AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
    return this.protocol(request).getResponse().getStatus();
  }

  private static String bearer(final String token) {
    return AuthUtils.AUTH_BEARER + token;
  }

  private Repo privateRepo() {
    return this.seedRepo(RepoType.NPM, uniqueRepoName("rps1361"), true, null);
  }

  /** The token {@code npm login} stores for the credentials. */
  private String login(final Repo repo, final String name, final String password) throws Exception {
    final var result =
        this.protocol(
            put(LOGIN, repo.getName(), name)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    this.objectMapper.writeValueAsBytes(
                        Map.of("name", name, "password", password))));

    assertThat(result.getResponse().getStatus()).isEqualTo(201);

    return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
  }

  private MvcResult logout(final Repo repo, final String authorization, final String token)
      throws Exception {
    final var request = delete(TOKEN, repo.getName(), token);

    if (authorization != null) {
      request.header(AUTHORIZATION, authorization);
    }

    return this.protocol(request);
  }

  private int whoami(final Repo repo, final String token) throws Exception {
    return this.status(get(WHOAMI, repo.getName()).header(AUTHORIZATION, bearer(token)));
  }

  /** A read of a private repo: 404 for a package that is absent when the token is accepted. */
  private int read(final Repo repo, final String token) throws Exception {
    return this.status(get(PACKUMENT, repo.getName()).header(AUTHORIZATION, bearer(token)));
  }

  private String deploySecret(final Repo repo) {
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
  @DisplayName("revokes the token of the login, so it is refused from then on")
  void logoutRevokesTheToken() throws Exception {
    final var repo = this.privateRepo();
    final User alice = this.createUser(uniqueUsername("alice"), UserRole.USER);
    final var token = this.login(repo, alice.getUsername(), VALID_PASSWORD);

    assertThat(this.whoami(repo, token)).isEqualTo(200);
    assertThat(this.read(repo, token)).isEqualTo(404);

    final var before = this.revokedTokenRepository.count();
    final var result = this.logout(repo, bearer(token), token);

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
    assertThat(JsonPath.<Boolean>read(result.getResponse().getContentAsString(), "$.ok")).isTrue();
    assertThat(this.revokedTokenRepository.count()).isEqualTo(before + 1);
    assertThat(this.whoami(repo, token)).isEqualTo(401);
    assertThat(this.read(repo, token)).isEqualTo(401);
    assertThat(
            this.status(delete(TOKEN, repo.getName(), token).header(AUTHORIZATION, bearer(token))))
        .isEqualTo(401);
  }

  @Test
  @DisplayName("stops the token on the other protocols as well, as they share the login token")
  void revokedTokenIsRefusedByAnotherProtocol() throws Exception {
    final var npm = this.privateRepo();
    final var maven = this.seedRepo(RepoType.MAVEN, uniqueRepoName("rps1361m"), true, null);
    final User alice = this.createUser(uniqueUsername("alice"), UserRole.USER);
    final var token = this.login(npm, alice.getUsername(), VALID_PASSWORD);
    final var artifact = "/{repo}/com/example/lib/1.0/lib-1.0.jar";

    assertThat(this.status(get(artifact, maven.getName()).header(AUTHORIZATION, bearer(token))))
        .isEqualTo(404);

    assertThat(this.logout(npm, bearer(token), token).getResponse().getStatus()).isEqualTo(200);

    assertThat(this.status(get(artifact, maven.getName()).header(AUTHORIZATION, bearer(token))))
        .isEqualTo(401);
  }

  @Test
  @DisplayName("lets the user revoke another token of the user with a password, and only that one")
  void revokesAnotherTokenOfTheSameUser() throws Exception {
    final var repo = this.privateRepo();
    final User alice = this.createUser(uniqueUsername("alice"), UserRole.USER);
    final var first = this.login(repo, alice.getUsername(), VALID_PASSWORD);
    // Another token of the same user, as another machine's login would hold.
    final var second = this.protocolBearerTokenFor(alice).substring(AuthUtils.AUTH_BEARER.length());
    assertThat(second).isNotEqualTo(first);

    final var result = this.logout(repo, basicAuth(alice.getUsername(), VALID_PASSWORD), first);

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(this.whoami(repo, first)).isEqualTo(401);
    assertThat(this.whoami(repo, second)).isEqualTo(200);
  }

  @Test
  @DisplayName("refuses the token of somebody else with 403, and it stays valid")
  void cannotRevokeSomebodyElsesToken() throws Exception {
    final var repo = this.privateRepo();
    final User alice = this.createUser(uniqueUsername("alice"), UserRole.USER);
    final User mallory = this.createUser(uniqueUsername("mallory"), UserRole.ADMIN);
    final var aliceToken = this.login(repo, alice.getUsername(), VALID_PASSWORD);
    final var malloryToken = this.login(repo, mallory.getUsername(), VALID_PASSWORD);

    final var result = this.logout(repo, bearer(malloryToken), aliceToken);

    assertThat(result.getResponse().getStatus()).isEqualTo(403);
    assertThat(this.whoami(repo, aliceToken)).isEqualTo(200);
    assertThat(this.whoami(repo, malloryToken)).isEqualTo(200);
  }

  @Test
  @DisplayName("refuses the secret of a deploy token with 403, and it keeps working")
  void cannotRevokeADeployTokenSecret() throws Exception {
    final var repo = this.privateRepo();
    final var secret = this.deploySecret(repo);

    final var result = this.logout(repo, bearer(secret), secret);

    assertThat(result.getResponse().getStatus()).isEqualTo(403);
    assertThat(this.read(repo, secret)).isEqualTo(404);
    assertThat(this.whoami(repo, secret)).isEqualTo(200);
  }

  @Test
  @DisplayName("revokes the token of a deploy-token login, and leaves the deploy token alone")
  void revokesTheTokenOfADeployTokenLogin() throws Exception {
    final var repo = this.privateRepo();
    final var secret = this.deploySecret(repo);
    final var token = this.login(repo, "typed-name", secret);

    assertThat(this.whoami(repo, token)).isEqualTo(200);

    assertThat(this.logout(repo, bearer(token), token).getResponse().getStatus()).isEqualTo(200);

    assertThat(this.whoami(repo, token)).isEqualTo(401);
    assertThat(this.whoami(repo, secret)).isEqualTo(200);
  }

  @Test
  @DisplayName("refuses a deploy-token login to revoke the token of a user, and the reverse")
  void cannotRevokeAcrossPrincipals() throws Exception {
    final var repo = this.privateRepo();
    final User alice = this.createUser(uniqueUsername("alice"), UserRole.USER);
    final var userToken = this.login(repo, alice.getUsername(), VALID_PASSWORD);
    final var deployToken = this.login(repo, alice.getUsername(), this.deploySecret(repo));

    assertThat(this.logout(repo, bearer(deployToken), userToken).getResponse().getStatus())
        .isEqualTo(403);
    assertThat(this.logout(repo, bearer(userToken), deployToken).getResponse().getStatus())
        .isEqualTo(403);
    assertThat(this.whoami(repo, userToken)).isEqualTo(200);
    assertThat(this.whoami(repo, deployToken)).isEqualTo(200);
  }

  @Test
  @DisplayName("answers 404 for a value that is no login token")
  void unknownToken() throws Exception {
    final var repo = this.privateRepo();
    final User alice = this.createUser(uniqueUsername("alice"), UserRole.USER);
    final var token = this.login(repo, alice.getUsername(), VALID_PASSWORD);
    final var panelToken = this.bearerTokenFor(alice).substring(AuthUtils.AUTH_BEARER.length());

    assertThat(this.logout(repo, bearer(token), "not-a-token").getResponse().getStatus())
        .isEqualTo(404);
    assertThat(this.logout(repo, bearer(token), panelToken).getResponse().getStatus())
        .isEqualTo(404);
    assertThat(this.whoami(repo, token)).isEqualTo(200);
  }

  @Test
  @DisplayName("needs credentials, on a public repo too, and a wrong password revokes nothing")
  void needsCredentials() throws Exception {
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("rps1361p"), false, null);
    final User alice = this.createUser(uniqueUsername("alice"), UserRole.USER);
    final var token = this.login(repo, alice.getUsername(), VALID_PASSWORD);

    final var anonymous = this.logout(repo, null, token).getResponse();
    final var wrong =
        this.logout(repo, basicAuth(alice.getUsername(), "not-the-password"), token).getResponse();

    assertThat(anonymous.getStatus()).isEqualTo(401);
    assertThat(anonymous.getHeader(WWW_AUTHENTICATE)).startsWith("Basic");
    assertThat(wrong.getStatus()).isEqualTo(401);
    assertThat(this.whoami(repo, token)).isEqualTo(200);
  }
}
