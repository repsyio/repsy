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

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * RPS-1039: the Docker and Helm OCI endpoints ({@code /v2/}) answer errors with the error body of
 * the OCI distribution specification, {@code {"errors":[{"code","message","detail"}]}}, while the
 * panel API and the classic Helm endpoints keep the panel envelope.
 *
 * <p>The pushes of Helm charts are pinned in {@code HelmChartControllerIT}, next to the behaviour
 * they reject.
 */
@DisplayName("OCI distribution error bodies")
class OciErrorBodyIT extends AbstractIntegrationTest {

  private static final String IMAGE = "app";
  private static final String UNKNOWN_DIGEST = "sha256:" + "b".repeat(64);

  private MockHttpServletResponse protocol(final MockHttpServletRequestBuilder request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  /** Asserts the distribution body: one error, and none of the panel envelope's keys. */
  private static void expectOciError(
      final MockHttpServletResponse response,
      final int status,
      final String code,
      final String message,
      final String detail)
      throws Exception {
    final var body = response.getContentAsString(StandardCharsets.UTF_8);

    assertThat(response.getStatus()).as(body).isEqualTo(status);
    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
    assertThat(JsonPath.<Map<String, Object>>read(body, "$")).containsOnlyKeys("errors");
    assertThat(JsonPath.<List<Object>>read(body, "$.errors")).hasSize(1);
    assertThat(JsonPath.<String>read(body, "$.errors[0].code")).isEqualTo(code);
    assertThat(JsonPath.<String>read(body, "$.errors[0].message")).isEqualTo(message);
    assertThat(JsonPath.<String>read(body, "$.errors[0].detail")).isEqualTo(detail);
  }

  private Repo dockerRepo(final boolean privateRepo) {
    return this.seedRepo(RepoType.DOCKER, uniqueRepoName("docker"), privateRepo, null);
  }

  // ---------------------------------------------------------------------------------------------
  // Docker
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("Docker: a manifest of an unknown image is 404 NAME_UNKNOWN")
  void dockerUnknownManifest() throws Exception {
    final var repo = this.dockerRepo(false);

    final var response =
        this.protocol(
            get("/v2/{repo}/{image}/manifests/latest", repo.getName(), IMAGE)
                .header(AUTHORIZATION, this.adminProtocolBearerToken()));

    expectOciError(response, 404, "NAME_UNKNOWN", "Image not found.", "imageNotFound");
  }

  @Test
  @DisplayName("Docker: an unknown blob is 404 BLOB_UNKNOWN")
  void dockerUnknownBlob() throws Exception {
    final var repo = this.dockerRepo(false);

    final var response =
        this.protocol(
            get("/v2/{repo}/{image}/blobs/{digest}", repo.getName(), IMAGE, UNKNOWN_DIGEST)
                .header(AUTHORIZATION, this.adminProtocolBearerToken()));

    expectOciError(response, 404, "BLOB_UNKNOWN", "Layer not found.", "layerNotFound");
  }

  @Test
  @DisplayName("Docker: an unknown repository is 404 NAME_UNKNOWN")
  void dockerUnknownRepository() throws Exception {
    final var response =
        this.protocol(
            get("/v2/{repo}/{image}/manifests/latest", uniqueRepoName("missing"), IMAGE)
                .header(AUTHORIZATION, this.adminProtocolBearerToken()));

    expectOciError(response, 404, "NAME_UNKNOWN", "unknownPath", "unknownPath");
  }

  @Test
  @DisplayName(
      "Docker: a private repo asks for credentials with 401 UNAUTHORIZED and the challenge")
  void dockerUnauthenticated() throws Exception {
    final var repo = this.dockerRepo(true);

    final var response =
        this.protocol(get("/v2/{repo}/{image}/manifests/latest", repo.getName(), IMAGE));

    expectOciError(
        response,
        401,
        "UNAUTHORIZED",
        "Authentication is required to access this resource.",
        "unauthorizedRequest");
    assertThat(response.getHeader(WWW_AUTHENTICATE))
        .startsWith("Bearer realm=")
        .contains("scope=\"repository:%s/%s:pull\"".formatted(repo.getName(), IMAGE));
  }

  /** RPS-1588: the ping addresses no image, so its challenge names no scope. */
  @Test
  @DisplayName("Docker: the registry ping is challenged without a scope")
  void dockerPingNamesNoScope() throws Exception {
    final var response = this.protocol(get("/v2/"));

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeader(WWW_AUTHENTICATE))
        .startsWith("Bearer realm=")
        .contains("service=\"repsy\"")
        .doesNotContain("scope=");
  }

  @Test
  @DisplayName("Docker: a blob upload without credentials is 401 UNAUTHORIZED")
  void dockerPushWithoutCredentials() throws Exception {
    final var repo = this.dockerRepo(false);

    final var response =
        this.protocol(post("/v2/{repo}/{image}/blobs/uploads/", repo.getName(), IMAGE));

    expectOciError(
        response,
        401,
        "UNAUTHORIZED",
        "The credentials are missing, invalid or expired, or they do not allow this action.",
        "unAuthorized");
    assertThat(response.getHeader(WWW_AUTHENTICATE))
        .contains("scope=\"repository:%s/%s:pull,push\"".formatted(repo.getName(), IMAGE));
  }

  // ---------------------------------------------------------------------------------------------
  // Helm
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("Helm OCI: a private repo asks for credentials with 401 UNAUTHORIZED")
  void helmOciUnauthenticated() throws Exception {
    final var repo = this.seedRepo(RepoType.HELM, uniqueRepoName("helm"), true, null);

    final var response =
        this.protocol(get("/v2/{repo}/{name}/manifests/1.0.0", repo.getName(), "payments"));

    expectOciError(
        response,
        401,
        "UNAUTHORIZED",
        "Authentication is required to access this resource.",
        "unauthorizedRequest");
    assertThat(response.getHeader(WWW_AUTHENTICATE)).startsWith("Basic realm=");
  }

  @Test
  @DisplayName("Helm OCI: an unknown manifest is 404 MANIFEST_UNKNOWN")
  void helmOciUnknownManifest() throws Exception {
    final var repo = this.seedRepo(RepoType.HELM, uniqueRepoName("helm"));

    final var response =
        this.protocol(
            get("/v2/{repo}/{name}/manifests/1.0.0", repo.getName(), "payments")
                .header(AUTHORIZATION, this.adminProtocolBearerToken()));

    expectOciError(response, 404, "MANIFEST_UNKNOWN", "Manifest not found.", "manifestNotFound");
  }

  @Test
  @DisplayName("Helm OCI: an unknown blob is 404 BLOB_UNKNOWN")
  void helmOciUnknownBlob() throws Exception {
    final var repo = this.seedRepo(RepoType.HELM, uniqueRepoName("helm"));

    final var response =
        this.protocol(
            get("/v2/{repo}/{name}/blobs/{digest}", repo.getName(), "payments", UNKNOWN_DIGEST)
                .header(AUTHORIZATION, this.adminProtocolBearerToken()));

    expectOciError(response, 404, "BLOB_UNKNOWN", "blobNotFound", "blobNotFound");
  }

  // ---------------------------------------------------------------------------------------------
  // What keeps the panel envelope
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("the classic Helm endpoints keep the panel envelope")
  void classicHelmKeepsEnvelope() throws Exception {
    final var repo = this.seedRepo(RepoType.HELM, uniqueRepoName("helm"));

    final var response =
        this.protocol(
            get("/{repo}/charts/missing-1.0.0.tgz", repo.getName())
                .header(AUTHORIZATION, this.adminProtocolBearerToken()));

    assertThat(response.getStatus()).isEqualTo(404);
    final var body = response.getContentAsString(StandardCharsets.UTF_8);
    assertThat(JsonPath.<Map<String, Object>>read(body, "$")).containsOnlyKeys(ENVELOPE_KEYS);
    assertThat(JsonPath.<String>read(body, "$.msgId")).isEqualTo("chartNotFound");
  }

  @Test
  @DisplayName("the panel API keeps the panel envelope")
  void panelKeepsEnvelope() throws Exception {
    final var response =
        this.mockMvc
            .perform(
                get("/api/docker/images/{repo}", uniqueRepoName("missing"))
                    .header(AUTHORIZATION, this.adminBearerToken())
                    .with(apiPort()))
            .andReturn()
            .getResponse();

    final var body = response.getContentAsString(StandardCharsets.UTF_8);
    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(JsonPath.<Map<String, Object>>read(body, "$")).containsOnlyKeys(ENVELOPE_KEYS);
    assertThat(JsonPath.<String>read(body, "$.type")).isEqualTo("ERROR");
  }
}
