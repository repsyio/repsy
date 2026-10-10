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
package io.repsy.os.server.protocols.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;

import io.repsy.os.AbstractIT;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * RPS-2059 pin: what a {@code HEAD} of each protocol answers before the pre-processors, on a
 * private repository, for a caller without credentials and for one with. The {@code HEAD} handlers
 * became the {@code HEAD} answer of their {@code GET} handler (the router's fallback); the order
 * "unknown repository, then credentials, then existence" and the routes that skip authentication
 * must not move.
 *
 * <p>Nothing is published: a path of a package that does not exist is enough to tell the
 * authentication step (401, before any lookup) from the lookup (404).
 */
@DisplayName("HEAD of the protocols: authentication and routing (RPS-2059)")
class ProtocolHeadWireIT extends AbstractIT {

  private MockHttpServletResponse headOf(
      final String pathTemplate, final String repoName, final String token) throws Exception {
    final var request =
        head("/" + pathTemplate.substring(1).replace("{repo}", repoName)).with(protocolPort());
    return this.mockMvc
        .perform(token == null ? request : request.header(AUTHORIZATION, token))
        .andReturn()
        .getResponse();
  }

  private void assertHead(
      final String path,
      final String repoName,
      final String token,
      final int status,
      final String contentType)
      throws Exception {
    final var response = this.headOf(path, repoName, token);

    assertThat(response.getStatus())
        .as("HEAD %s with%s token", path, token == null ? "out" : "")
        .isEqualTo(status);
    assertThat(response.getContentAsByteArray()).as("HEAD %s body", path).isEmpty();
    assertThat(response.getContentType()).as("HEAD %s Content-Type", path).isEqualTo(contentType);
  }

  private static final List<String> CHALLENGED =
      List.of(
          // Go
          "/{repo}/example.com/m/@v/v1.0.0.zip",
          "/{repo}/example.com/m/@v/list",
          // NuGet
          "/{repo}/v3/package/p.id/1.0.0/p.id.1.0.0.nupkg",
          "/{repo}/v3/package/p.id/1.0.0/p.id.nuspec",
          "/{repo}/v3/package/p.id/index.json",
          "/{repo}/v3/registration/p.id/index.json",
          "/{repo}/v3/registration/p.id/1.0.0.json",
          // PyPI
          "/{repo}/simple/",
          "/{repo}/simple/p-id/",
          "/{repo}/p-id/-/p_id-1.0.tar.gz",
          // Ruby
          "/{repo}/versions",
          "/{repo}/names",
          "/{repo}/specs.4.8.gz",
          "/{repo}/info/p-id",
          "/{repo}/gems/p-id-1.0.gem",
          "/{repo}/quick/Marshal.4.8/p-id-1.0.gemspec.rz",
          // Cargo
          "/{repo}/api/v1/crates/p-id/1.0.0/download",
          "/{repo}/p-/id/p-id");

  @Test
  @DisplayName("a private repo challenges an anonymous HEAD of every GET route before any lookup")
  void privateRepoChallengesAnonymousHead() throws Exception {
    for (final var path : CHALLENGED) {
      final var type = typeOf(path);
      final var repo = this.seedRepo(type, uniqueRepoName("head"), true, null);

      final var anonymous = this.headOf(path, repo.getName(), null);

      assertThat(anonymous.getStatus()).as("anonymous HEAD %s", path).isEqualTo(401);
    }
  }

  @Test
  @DisplayName("an authenticated HEAD of what was never published is a 404 with no body")
  void authenticatedHeadOfMissingIs404() throws Exception {
    final var token = this.adminProtocolBearerToken();

    for (final var path : CHALLENGED) {
      final var type = typeOf(path);
      final var repo = this.seedRepo(type, uniqueRepoName("head"), true, null);
      final var expected =
          path.endsWith("/versions")
                  || path.endsWith("/names")
                  || path.endsWith("/specs.4.8.gz")
                  || path.endsWith("/simple/")
              ? 200
              : 404;

      final var response = this.headOf(path, repo.getName(), token);

      assertThat(response.getStatus()).as("HEAD %s", path).isEqualTo(expected);
      assertThat(response.getContentAsByteArray()).as("HEAD %s body", path).isEmpty();
    }
  }

  @Test
  @DisplayName("a HEAD of an unknown repository is a 404 for every protocol")
  void unknownRepoIs404() throws Exception {
    final var token = this.adminProtocolBearerToken();

    for (final var path : CHALLENGED) {
      final var response = this.headOf(path, uniqueRepoName("no-repo"), token);

      assertThat(response.getStatus()).as("HEAD %s", path).isEqualTo(404);
    }
  }

  @Test
  @DisplayName("the NuGet service index and the Cargo config.json skip authentication on HEAD")
  void documentsSkipAuthentication() throws Exception {
    final var nuget = this.seedRepo(RepoType.NUGET, uniqueRepoName("head"), true, null);
    final var cargo = this.seedRepo(RepoType.CARGO, uniqueRepoName("head"), true, null);

    this.assertHead("/{repo}/v3/index.json", nuget.getName(), null, 200, "application/json");
    this.assertHead("/{repo}/config.json", cargo.getName(), null, 200, "application/json");
  }

  @Test
  @DisplayName("a path no route of the protocol knows is a 404; who sends an empty body")
  void unknownPathIs404() throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var seen = new java.util.ArrayList<String>();

    for (final var type : List.of(RepoType.GOLANG, RepoType.NUGET, RepoType.PYPI, RepoType.RUBY)) {
      final var repo = this.seedRepo(type, uniqueRepoName("head"), false, null);
      final var response = this.headOf("/{repo}/this/path/never/existed", repo.getName(), token);

      seen.add(
          "%s:%d:%s:%s"
              .formatted(
                  type,
                  response.getStatus(),
                  response.getContentType(),
                  response.getContentAsByteArray().length == 0 ? "empty" : "body"));
    }

    assertThat(seen).containsExactly(EXPECTED_UNKNOWN_PATH.toArray(String[]::new));
  }

  /**
   * Go answers any path itself (a text/plain 404 for what it does not have), NuGet and the rest
   * fall to the router's "unknownPath" error. Before RPS-2059 PyPI and Ruby had a catch-all HEAD
   * handler answering a bare 404 (no Content-Type); with the HEAD of their GET handlers a path none
   * of them owns is the router's "unknownPath" like NuGet's. Same status, same (stripped) body, and
   * the 404 carries the error Content-Type.
   */
  private static final List<String> EXPECTED_UNKNOWN_PATH =
      List.of(
          "GOLANG:404:text/plain:empty",
          "NUGET:404:application/json:body",
          "PYPI:404:application/json:body",
          "RUBY:404:application/json:body");

  private static RepoType typeOf(final String path) {
    if (path.contains("@v/")) {
      return RepoType.GOLANG;
    }
    if (path.contains("/v3/")) {
      return RepoType.NUGET;
    }
    if (path.contains("/simple/") || path.contains("/-/")) {
      return RepoType.PYPI;
    }
    if (path.contains("/api/v1/crates/") || path.endsWith("/p-id") && path.contains("/p-/id/")) {
      return RepoType.CARGO;
    }
    return RepoType.RUBY;
  }
}
