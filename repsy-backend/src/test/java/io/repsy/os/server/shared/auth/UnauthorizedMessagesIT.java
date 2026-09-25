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
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.shared.token.entities.RepoDeployToken;
import io.repsy.os.server.shared.token.repositories.RepoDeployTokenRepository;
import io.repsy.os.server.shared.token.utils.DeployTokenHash;
import io.repsy.os.server.shared.token.utils.TokenUsernameGenerator;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * RPS-1435: the 401 of the Docker token endpoint and of Go says why in a form the client prints. A
 * wrong Docker login used to print {@code unauthorized: } with nothing after it, and the {@code go}
 * command printed a bare {@code 401}, because it shows a body only when it is {@code text/plain}.
 *
 * <p>An unknown user and a wrong password must keep one and the same message: naming the cause
 * would tell a caller whether an account exists.
 */
@DisplayName("401 messages of the Docker token endpoint and Go (RPS-1435)")
class UnauthorizedMessagesIT extends AbstractIntegrationTest {

  private static final String GENERIC =
      "The credentials are missing, invalid or expired, or they do not allow this action.";
  private static final String MODULE = "example.com/mod";

  @Autowired private RepoDeployTokenRepository deployTokenRepository;

  private MockHttpServletResponse protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private Repo privateRepo(final RepoType type) {
    return this.seedRepo(type, uniqueRepoName("rps1435"), true, null);
  }

  private String expiredDeployTokenSecret(final Repo repo) {
    final var secret = TokenFactory.deployToken();
    final var entity = new RepoDeployToken();

    entity.setRepo(this.repoRepository.getReferenceById(repo.getId()));
    entity.setName("token-" + secret.substring(0, 6));
    entity.setUsername(TokenUsernameGenerator.deployTokenUsername());
    entity.setToken(DeployTokenHash.hash(secret));
    entity.setReadOnly(false);
    entity.setExpirationDate(Instant.now().minus(1, ChronoUnit.DAYS));
    entity.setTokenDurationDay(30);
    this.deployTokenRepository.save(entity);
    this.entityManager.flush();

    return secret;
  }

  /** The distribution error body: one {@code UNAUTHORIZED} error with the message and its id. */
  private static void expectOciUnauthorized(
      final MockHttpServletResponse response, final String message, final String detail)
      throws Exception {
    final var body = response.getContentAsString(StandardCharsets.UTF_8);

    assertThat(response.getStatus()).as(body).isEqualTo(401);
    assertThat(response.getHeader(WWW_AUTHENTICATE)).startsWith("Basic realm=");
    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
    assertThat(JsonPath.<Map<String, Object>>read(body, "$")).containsOnlyKeys("errors");
    assertThat(JsonPath.<List<Object>>read(body, "$.errors")).hasSize(1);
    assertThat(JsonPath.<String>read(body, "$.errors[0].code")).isEqualTo("UNAUTHORIZED");
    assertThat(JsonPath.<String>read(body, "$.errors[0].message")).isEqualTo(message);
    assertThat(JsonPath.<String>read(body, "$.errors[0].detail")).isEqualTo(detail);
  }

  private static void expectGoUnauthorized(
      final MockHttpServletResponse response, final String message) throws Exception {
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeader(WWW_AUTHENTICATE)).startsWith("Basic realm=");
    assertThat(response.getContentType()).startsWith(MediaType.TEXT_PLAIN_VALUE);
    assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(message);
  }

  private MockHttpServletResponse dockerToken(final String authorization, final String scope)
      throws Exception {
    final var request = get("/v2/token");

    if (authorization != null) {
      request.header(AUTHORIZATION, authorization);
    }

    if (scope != null) {
      request.param("scope", scope);
    }

    return this.protocol(request);
  }

  private MockHttpServletResponse goList(final Repo repo, final String authorization)
      throws Exception {
    final var request = get("/{repo}/" + MODULE + "/@v/list", repo.getName());

    if (authorization != null) {
      request.header(AUTHORIZATION, authorization);
    }

    return this.protocol(request);
  }

  // ---------------------------------------------------------------------------------------------
  // Docker token endpoint
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("Docker: a wrong password is 401 UNAUTHORIZED with a message, not an empty body")
  void dockerWrongPassword() throws Exception {
    final var user = this.createUser(uniqueUsername("dk"), UserRole.ADMIN);

    final var response =
        this.dockerToken(basicAuth(user.getUsername(), "not-the-password"), "repository:r/i:push");

    expectOciUnauthorized(response, GENERIC, "unAuthorized");
  }

  @Test
  @DisplayName("Docker: an unknown user gets the very same 401 as a wrong password")
  void dockerUnknownUserIsIndistinguishable() throws Exception {
    final var user = this.createUser(uniqueUsername("dk"), UserRole.ADMIN);
    final var wrongPassword =
        this.dockerToken(basicAuth(user.getUsername(), "not-the-password"), "repository:r/i:push");
    final var unknownUser =
        this.dockerToken(
            basicAuth(uniqueUsername("nobody"), "not-the-password"), "repository:r/i:push");

    expectOciUnauthorized(unknownUser, GENERIC, "unAuthorized");
    assertThat(unknownUser.getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(wrongPassword.getContentAsString(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("Docker: an expired deploy token is named as expired")
  void dockerExpiredDeployToken() throws Exception {
    final var repo = this.privateRepo(RepoType.DOCKER);
    final var secret = this.expiredDeployTokenSecret(repo);

    final var response =
        this.dockerToken(basicAuth("whoever", secret), "repository:" + repo.getName() + "/i:push");

    expectOciUnauthorized(response, "Deploy token expired.", "deployTokenExpired");
  }

  @Test
  @DisplayName("Docker: a push scope without credentials asks for authentication")
  void dockerPushScopeWithoutCredentials() throws Exception {
    final var response = this.dockerToken(null, "repository:r/i:push");

    expectOciUnauthorized(
        response, "Authentication is required to access this resource.", "unauthorizedRequest");
  }

  @Test
  @DisplayName("Docker: a pull of a private repo without credentials is the generic 401")
  void dockerAnonymousPrivatePull() throws Exception {
    final var repo = this.privateRepo(RepoType.DOCKER);

    final var response = this.dockerToken(null, "repository:" + repo.getName() + "/i:pull");

    expectOciUnauthorized(response, GENERIC, "unAuthorized");
  }

  @Test
  @DisplayName("Docker: a password-grant form with a wrong password is the same 401")
  void dockerPasswordGrantWrongPassword() throws Exception {
    final var user = this.createUser(uniqueUsername("dk"), UserRole.ADMIN);

    final var response =
        this.protocol(
            post("/v2/token")
                .param("grant_type", "password")
                .param("username", user.getUsername())
                .param("password", "not-the-password")
                .param("scope", "repository:r/i:pull"));

    expectOciUnauthorized(response, GENERIC, "unAuthorized");
  }

  // ---------------------------------------------------------------------------------------------
  // Go
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("Go: a request without credentials is 401 with a text/plain message")
  void goWithoutCredentials() throws Exception {
    final var repo = this.privateRepo(RepoType.GOLANG);

    expectGoUnauthorized(
        this.goList(repo, null), "Authentication is required to access this resource.");
  }

  @Test
  @DisplayName("Go: a wrong password is 401 with the generic text/plain message")
  void goWrongPassword() throws Exception {
    final var repo = this.privateRepo(RepoType.GOLANG);
    final var user = this.createUser(uniqueUsername("go"), UserRole.ADMIN);

    expectGoUnauthorized(
        this.goList(repo, basicAuth(user.getUsername(), "not-the-password")), GENERIC);
  }

  @Test
  @DisplayName("Go: an unknown user gets the very same 401 as a wrong password")
  void goUnknownUserIsIndistinguishable() throws Exception {
    final var repo = this.privateRepo(RepoType.GOLANG);

    expectGoUnauthorized(
        this.goList(repo, basicAuth(uniqueUsername("nobody"), "not-the-password")), GENERIC);
  }

  @Test
  @DisplayName("Go: an expired deploy token is named as expired")
  void goExpiredDeployToken() throws Exception {
    final var repo = this.privateRepo(RepoType.GOLANG);
    final var secret = this.expiredDeployTokenSecret(repo);

    expectGoUnauthorized(this.goList(repo, basicAuth("whoever", secret)), "Deploy token expired.");
  }

  @Test
  @DisplayName("Go: valid credentials still read the module list")
  void goValidCredentials() throws Exception {
    final var repo = this.privateRepo(RepoType.GOLANG);

    final var response = this.goList(repo, this.adminProtocolBearerToken());

    assertThat(response.getStatus()).isNotEqualTo(401);
  }
}
