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
package io.repsy.os.server.protocols.docker.protocol.pre_processors;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.server.core.UrlParserProperties;
import io.repsy.os.server.shared.utils.ProtocolContextUtils;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

@DisplayName("DockerAuthChallenge")
class DockerAuthChallengeTest {

  private static MockHttpServletRequest request(final String method, final String path) {
    final var request = new MockHttpServletRequest(method, path);
    request.setScheme("https");
    request.setServerName("registry.example.com");
    request.setServerPort(443);

    return request;
  }

  private static ProtocolContext contextOf(final String repoName, final String relativePath) {
    final var context = new ProtocolContext();

    context.addProperty(
        "urlProperties",
        UrlParserProperties.builder()
            .repoName(repoName)
            .relativePath(new RelativePath(relativePath))
            .repoInfo(RepoInfo.builder().storageKey(UUID.randomUUID()).name(repoName).build())
            .build());

    return context;
  }

  private static String challengeWithScope(final String scope) {
    return "Bearer realm=\"https://registry.example.com/v2/token\",service=\"repsy\",scope=\""
        + scope
        + "\"";
  }

  /** RPS-1588: the registry ping addresses no image, so its challenge names no scope. */
  @Test
  @DisplayName("points the client to the token endpoint of the host it called, with no scope")
  void pointsToTokenEndpoint() {
    final var request = request("GET", "/v2/");

    assertThat(DockerAuthChallenge.of(request, null, Permission.NONE))
        .isEqualTo("Bearer realm=\"https://registry.example.com/v2/token\",service=\"repsy\"");
  }

  @ParameterizedTest
  @CsvSource({"READ,pull", "WRITE,'pull,push'", "MANAGE,delete"})
  @DisplayName("names the scope the request needs (RPS-1588)")
  void namesTheScopeTheRequestNeeds(final Permission permission, final String actions) {
    final var request = request("GET", "/v2/repo/app/manifests/latest");

    assertThat(DockerAuthChallenge.of(request, "repo/app", permission))
        .isEqualTo(challengeWithScope("repository:repo/app:" + actions));
  }

  @Test
  @DisplayName("names no scope for a request that needs no grant, or addresses no image")
  void namesNoScopeWithoutGrantOrImage() {
    final var request = request("GET", "/v2/");
    final var noScope = "Bearer realm=\"https://registry.example.com/v2/token\",service=\"repsy\"";

    assertThat(DockerAuthChallenge.of(request, "repo/app", Permission.NONE)).isEqualTo(noScope);
    assertThat(DockerAuthChallenge.of(request, "repo/app", null)).isEqualTo(noScope);
    assertThat(DockerAuthChallenge.of(request, " ", Permission.READ)).isEqualTo(noScope);
  }

  @Test
  @DisplayName("reads the image from the path of the request and the permission from its handler")
  void readsTheRouteOfTheRequest() {
    final var request = request("DELETE", "/v2/repo/app/manifests/latest");
    final var context = contextOf("repo", "/app/manifests/latest");

    assertThat(DockerAuthChallenge.of(context, request, Map.of("permission", Permission.MANAGE)))
        .isEqualTo(challengeWithScope("repository:repo/app:delete"));
    assertThat(DockerAuthChallenge.of(context, request, Map.of("permission", Permission.WRITE)))
        .isEqualTo(challengeWithScope("repository:repo/app:pull,push"));
  }

  @Test
  @DisplayName("names no scope on the ping, which has an empty repo and no image")
  void namesNoScopeOnThePing() {
    final var request = request("GET", "/v2/");
    final var context = ProtocolContextUtils.createWithEmptyRepo("", new RelativePath("/v2"));

    assertThat(DockerAuthChallenge.of(context, request, Map.of("permission", Permission.NONE)))
        .doesNotContain("scope=");
    assertThat(DockerAuthChallenge.of(context, request, Map.of("permission", Permission.READ)))
        .doesNotContain("scope=");
  }

  @Test
  @DisplayName("names no scope for a path of a repo that has no image segment")
  void namesNoScopeWithoutImageSegment() {
    final var request = request("GET", "/v2/repo");

    assertThat(
            DockerAuthChallenge.of(
                contextOf("repo", ""), request, Map.of("permission", Permission.READ)))
        .doesNotContain("scope=");
  }

  /** RPS-1434: a token that was issued for less tells the client which scope to ask for. */
  @Test
  @DisplayName("names the scope to ask for and the insufficient_scope error")
  void insufficientScopeNamesTheScope() {
    final var request = new MockHttpServletRequest("DELETE", "/v2/repo/app/manifests/latest");
    request.setScheme("https");
    request.setServerName("registry.example.com");
    request.setServerPort(443);

    assertThat(DockerAuthChallenge.insufficientScope(request, "repository:repo/app:delete"))
        .isEqualTo(
            "Bearer realm=\"https://registry.example.com/v2/token\","
                + "service=\"repsy\",scope=\"repository:repo/app:delete\","
                + "error=\"insufficient_scope\"");
  }
}
