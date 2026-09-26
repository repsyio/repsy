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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.shared.token.entities.RepoDeployToken;
import io.repsy.os.server.shared.token.repositories.RepoDeployTokenRepository;
import io.repsy.os.server.shared.token.utils.DeployTokenHash;
import io.repsy.os.server.shared.token.utils.TokenUsernameGenerator;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * RPS-1044: a protocol endpoint takes its credential from the {@code Authorization} header only. A
 * credential in a URL ends up in browser history, proxy and access logs and {@code Referer}
 * headers, and no supported package manager sends one, so {@code ?token=} carrying a protocol JWT
 * or a deploy token is ignored by every protocol.
 *
 * <p>Each refusal is paired with the same credential accepted in the header, so a test fails if the
 * fixture stops being a valid credential, not only if the query parameter starts working again.
 */
@DisplayName("A credential in the ?token= query parameter is refused by every protocol")
class QueryTokenRefusedIT extends AbstractIntegrationTest {

  private static final String MODULE = "example.com/mod";

  @Autowired private RepoDeployTokenRepository deployTokenRepository;

  /** A read that needs authentication on a private repo of each protocol. */
  static Stream<Arguments> privateReads() {
    return Stream.of(
        Arguments.of(RepoType.MAVEN, "/{repo}/com/example/lib/1.0/lib-1.0.pom"),
        Arguments.of(RepoType.NPM, "/{repo}/some-package"),
        Arguments.of(RepoType.PYPI, "/{repo}/simple/some-package/"),
        Arguments.of(RepoType.HELM, "/{repo}/index.yaml"),
        Arguments.of(RepoType.CARGO, "/{repo}/so/me/some-crate"),
        Arguments.of(RepoType.GOLANG, "/{repo}/" + MODULE + "/@v/list"),
        Arguments.of(RepoType.RUBY, "/{repo}/names"),
        Arguments.of(RepoType.DOCKER, "/v2/{repo}/some-image/manifests/latest"));
  }

  private MvcResult protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn();
  }

  private int status(final AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
    return this.protocol(request).getResponse().getStatus();
  }

  private String protocolJwt(final User user) {
    return this.jwtUtils.createProtocolToken(
        user.getId(), user.getUsername(), Duration.ofMinutes(30), user.getTokenVersion());
  }

  /** A deploy token as a package manager holds it: the username and the secret. */
  private record DeployCredential(String username, String secret) {}

  /** Inserts a deploy token for the repo. */
  private DeployCredential seedDeployToken(final Repo repo) {
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

    return new DeployCredential(entity.getUsername(), secret);
  }

  /**
   * The bearer value a package manager sends for a deploy token: the secret itself, except for
   * Docker, whose clients exchange the Basic credentials for a JWT at {@code /v2/token} first.
   */
  private String bearerValueFor(final RepoType type, final Repo repo, final DeployCredential token)
      throws Exception {
    if (type != RepoType.DOCKER) {
      return token.secret();
    }

    final var result =
        this.protocol(
            get("/v2/token")
                .param("scope", "repository:%s/some-image:pull".formatted(repo.getName()))
                .header(AUTHORIZATION, basicAuth(token.username(), token.secret())));

    assertThat(result.getResponse().getStatus()).isEqualTo(200);

    return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
  }

  @Nested
  @DisplayName("on a private repo")
  class PrivateRepo {

    @ParameterizedTest(
        name = "{0}: a protocol JWT is refused in ?token= and accepted in the header")
    @MethodSource("io.repsy.os.server.shared.auth.QueryTokenRefusedIT#privateReads")
    void protocolJwt(final RepoType type, final String path) throws Exception {
      final var user = createUser(uniqueUsername("qryjwt"), UserRole.USER);
      final var repo = seedRepo(type, uniqueRepoName("rps1044"), true, null);
      final var jwt = QueryTokenRefusedIT.this.protocolJwt(user);

      assertThat(status(get(path, repo.getName()).param("token", jwt))).isEqualTo(401);
      assertThat(
              status(get(path, repo.getName()).header(AUTHORIZATION, AuthUtils.AUTH_BEARER + jwt)))
          .isNotEqualTo(401);
    }

    @ParameterizedTest(
        name = "{0}: a deploy token is refused in ?token= and accepted in the header")
    @MethodSource("io.repsy.os.server.shared.auth.QueryTokenRefusedIT#privateReads")
    void deployToken(final RepoType type, final String path) throws Exception {
      final var repo = seedRepo(type, uniqueRepoName("rps1044"), true, null);
      final var token = seedDeployToken(repo);
      final var bearer = bearerValueFor(type, repo, token);

      assertThat(status(get(path, repo.getName()).param("token", bearer))).isEqualTo(401);
      assertThat(status(get(path, repo.getName()).param("token", token.secret()))).isEqualTo(401);
      assertThat(
              status(
                  get(path, repo.getName()).header(AUTHORIZATION, AuthUtils.AUTH_BEARER + bearer)))
          .isNotEqualTo(401);
    }

    @ParameterizedTest(name = "{0}: ?token= does not replace an Authorization header")
    @MethodSource("io.repsy.os.server.shared.auth.QueryTokenRefusedIT#privateReads")
    void queryTokenDoesNotOverrideTheHeader(final RepoType type, final String path)
        throws Exception {
      final var user = createUser(uniqueUsername("qryhdr"), UserRole.USER);
      final var repo = seedRepo(type, uniqueRepoName("rps1044"), true, null);
      final var jwt = QueryTokenRefusedIT.this.protocolJwt(user);

      // The valid header must win over a garbage query token, not the other way round.
      assertThat(
              status(
                  get(path, repo.getName())
                      .param("token", "not-a-token")
                      .header(AUTHORIZATION, AuthUtils.AUTH_BEARER + jwt)))
          .isNotEqualTo(401);
    }
  }

  @Nested
  @DisplayName("on a public repo")
  class PublicRepo {

    @Test
    @DisplayName("a write with the credential only in ?token= is refused for Maven")
    void mavenWrite() throws Exception {
      final var user = createUser(uniqueUsername("qrymvn"), UserRole.USER);
      final var repo = seedRepo(RepoType.MAVEN, uniqueRepoName("rps1044"), false, null);
      final var path = "/{repo}/com/example/lib/1.0/lib-1.0.pom";

      assertThat(
              status(
                  put(path, repo.getName())
                      .param("token", protocolJwt(user))
                      .content("<project/>")))
          .isEqualTo(401);
    }

    @Test
    @DisplayName("a write with the credential only in ?token= is refused for npm")
    void npmWrite() throws Exception {
      final var user = createUser(uniqueUsername("qrynpm"), UserRole.USER);
      final var repo = seedRepo(RepoType.NPM, uniqueRepoName("rps1044"), false, null);

      assertThat(
              status(
                  put("/{repo}/some-package", repo.getName())
                      .param("token", protocolJwt(user))
                      .contentType("application/json")
                      .content("{}")))
          .isEqualTo(401);
    }
  }
}
