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
package io.repsy.os.server.protocols.shared.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * RPS-1047: a protocol endpoint that answers 401 names the way to authenticate, RFC 9110 section
 * 15.5.2, whether the request carried no credentials or credentials that were rejected. Every
 * protocol is pinned against its own challenge: the Basic realms differ per protocol, and Docker
 * announces a Bearer realm that points to its token endpoint.
 */
@DisplayName("Protocol 401 answers carry the challenge of the protocol")
class ProtocolAuthChallengeIT extends AbstractIntegrationTest {

  private static final String REPOSITORY_REALM = "Basic realm=\"Repsy Managed Repository\"";
  private static final String NPM_REALM = "Basic realm=\"Repsy Managed Registry\"";

  /**
   * RPS-1209: a refused npm Bearer token is challenged with both schemes, Bearer first, because the
   * npm client takes the first scheme it knows from the first value and prints its "token seems to
   * be invalid" text for Bearer, "Incorrect or missing password" for Basic.
   */
  private static final String NPM_BEARER_REALM =
      "Bearer realm=\"Repsy Managed Registry\", " + NPM_REALM;

  private static final String GO_REALM = "Basic realm=\"Repsy Go Module Proxy\"";
  private static final String HELM_REALM = "Basic realm=\"Repsy\"";
  private static final String DOCKER_REALM_PREFIX = "Bearer realm=";

  /** Type, a read of a private repo of it, and the challenge a 401 of it carries. */
  private static Stream<Arguments> protocols() {
    return Stream.of(
        Arguments.of(RepoType.MAVEN, "/{repo}/com/example/lib/1.0/lib-1.0.pom", REPOSITORY_REALM),
        Arguments.of(RepoType.NPM, "/{repo}/some-package", NPM_REALM),
        Arguments.of(RepoType.CARGO, "/{repo}/de/pl/demo-crate", REPOSITORY_REALM),
        Arguments.of(RepoType.NUGET, "/{repo}/v3/package/demo/index.json", REPOSITORY_REALM),
        Arguments.of(RepoType.PYPI, "/{repo}/simple/demo/", REPOSITORY_REALM),
        Arguments.of(RepoType.RUBY, "/{repo}/names", REPOSITORY_REALM),
        Arguments.of(RepoType.GOLANG, "/{repo}/example.com/demo/@v/list", GO_REALM),
        Arguments.of(RepoType.HELM, "/{repo}/index.yaml", HELM_REALM),
        Arguments.of(RepoType.HELM, "/v2/{repo}/payments/manifests/1.0.0", HELM_REALM),
        Arguments.of(RepoType.DOCKER, "/v2/{repo}/app/manifests/latest", DOCKER_REALM_PREFIX));
  }

  private MockHttpServletResponse protocol(final MockHttpServletRequestBuilder request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private String seedPrivateRepo(final RepoType type) {
    return this.seedRepo(type, uniqueRepoName("challenge"), true, null).getName();
  }

  private static void expectChallenge(
      final MockHttpServletResponse response, final String challenge) {

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeaders(WWW_AUTHENTICATE)).hasSize(1);

    if (challenge.endsWith("realm=") && challenge.startsWith("Bearer")) {
      assertThat(response.getHeader(WWW_AUTHENTICATE)).startsWith(challenge);
    } else {
      assertThat(response.getHeader(WWW_AUTHENTICATE)).isEqualTo(challenge);
    }
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("protocols")
  @DisplayName("a request without credentials gets the challenge")
  void withoutCredentials(final RepoType type, final String path, final String challenge)
      throws Exception {
    final var repoName = this.seedPrivateRepo(type);

    expectChallenge(this.protocol(get(path, repoName)), challenge);
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("protocols")
  @DisplayName("a wrong Basic password gets the challenge")
  void wrongBasicPassword(final RepoType type, final String path, final String challenge)
      throws Exception {
    final var repoName = this.seedPrivateRepo(type);
    final var basic = basicAuth(SEEDED_ADMIN_USERNAME, "not-the-password");

    expectChallenge(this.protocol(get(path, repoName).header(AUTHORIZATION, basic)), challenge);
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("protocols")
  @DisplayName("an invalid Bearer token gets the challenge")
  void invalidBearerToken(final RepoType type, final String path, final String challenge)
      throws Exception {
    final var repoName = this.seedPrivateRepo(type);

    final var expected = NPM_REALM.equals(challenge) ? NPM_BEARER_REALM : challenge;

    expectChallenge(
        this.protocol(get(path, repoName).header(AUTHORIZATION, "Bearer not.a.token")), expected);
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("protocols")
  @DisplayName("an unsupported authentication scheme gets the challenge")
  void unsupportedScheme(final RepoType type, final String path, final String challenge)
      throws Exception {
    final var repoName = this.seedPrivateRepo(type);

    expectChallenge(
        this.protocol(get(path, repoName).header(AUTHORIZATION, "Digest username=\"x\"")),
        challenge);
  }

  @Test
  @DisplayName("Docker: a push with an invalid Bearer token gets the Bearer challenge")
  void dockerPushWithInvalidToken() throws Exception {
    final var repoName = this.seedRepo(RepoType.DOCKER, uniqueRepoName("challenge")).getName();

    final var response =
        this.protocol(
            post("/v2/{repo}/app/blobs/uploads/", repoName)
                .header(AUTHORIZATION, "Bearer not.a.token"));

    expectChallenge(response, DOCKER_REALM_PREFIX);
  }

  @Test
  @DisplayName("Docker: a push without credentials gets the Bearer challenge")
  void dockerPushWithoutCredentials() throws Exception {
    final var repoName = this.seedRepo(RepoType.DOCKER, uniqueRepoName("challenge")).getName();

    final var response = this.protocol(post("/v2/{repo}/app/blobs/uploads/", repoName));

    expectChallenge(response, DOCKER_REALM_PREFIX);
  }
}
