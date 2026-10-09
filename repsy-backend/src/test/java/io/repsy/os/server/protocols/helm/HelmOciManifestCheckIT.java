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
package io.repsy.os.server.protocols.helm;

import static io.repsy.os.server.protocols.helm.HelmChartFixtures.OCI_CONFIG_TYPE;
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.OCI_LAYER_TYPE;
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.OCI_MANIFEST_TYPE;
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.chart;
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.digest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Transactional;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;

/**
 * RPS-2089: {@code HEAD /v2/{repo}/{chart}/manifests/{ref}} must answer exactly like {@code GET} of
 * the same reference (OCI distribution spec: "HEAD ... MUST be identical to GET ... except no body
 * is returned"), for both a tag and a digest reference. A Helm client (Helm, ORAS) calls HEAD before
 * GET to check manifest existence.
 */
@DisplayName("Helm OCI manifest check (HEAD)")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class HelmOciManifestCheckIT extends AbstractIntegrationTest {

  private static final String CHART = "payments";
  private static final String OCTET_STREAM = "application/octet-stream";

  /** {@code @Async}, so it cannot see this class's uncommitted rows. */
  @MockitoBean private UsageUpdateService usageUpdateService;

  private Repo helmRepo() {
    return this.seedRepo(RepoType.HELM, uniqueRepoName("helm"));
  }

  private Repo privateHelmRepo() {
    return this.seedRepo(RepoType.HELM, uniqueRepoName("helm"), true, null);
  }

  // ----
  // Requests
  // ----

  private MockHttpServletResponse uploadBlob(
      final Repo repo, final byte[] bytes, final String digest, final String token)
      throws Exception {
    final var start =
        this.mockMvc
            .perform(
                post("/v2/{repo}/{name}/blobs/uploads/", repo.getName(), CHART)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(start.getStatus()).as("blob upload start").isEqualTo(202);
    final var location = start.getHeader("Location");
    final var uploadId = location.substring(location.lastIndexOf('/') + 1);

    return this.mockMvc
        .perform(
            put("/v2/{repo}/{name}/blobs/uploads/{id}", repo.getName(), CHART, uploadId)
                .param("digest", digest)
                .contentType(OCTET_STREAM)
                .content(bytes)
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse putManifest(
      final Repo repo,
      final String reference,
      final String manifest,
      final String token)
      throws Exception {
    return this.mockMvc
        .perform(
            put("/v2/{repo}/{name}/manifests/{reference}", repo.getName(), CHART, reference)
                .contentType(OCI_MANIFEST_TYPE)
                .content(manifest)
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  /**
   * Uploads {@code chartBytes} as the layer, then puts a manifest for it under {@code reference}.
   */
  private void pushOci(
      final Repo repo,
      final String reference,
      final byte[] chartBytes,
      final String token)
      throws Exception {
    final var layerDigest = digest("SHA-256", chartBytes);
    final var upload = this.uploadBlob(repo, chartBytes, layerDigest, token);
    assertThat(upload.getStatus()).as("chart layer upload").isEqualTo(201);

    final var config = "{}".getBytes(StandardCharsets.UTF_8);
    final var manifest =
        ("{\"schemaVersion\":2,\"mediaType\":\"%s\",\"config\":{\"mediaType\":\"%s\","
                + "\"digest\":\"%s\",\"size\":%d},\"layers\":[{\"mediaType\":\"%s\","
                + "\"digest\":\"%s\",\"size\":%d}]}")
            .formatted(
                OCI_MANIFEST_TYPE,
                OCI_CONFIG_TYPE,
                digest("SHA-256", config),
                config.length,
                OCI_LAYER_TYPE,
                layerDigest,
                chartBytes.length);

    final var pushed = this.putManifest(repo, reference, manifest, token);
    assertThat(pushed.getStatus()).as(pushed.getContentAsString()).isEqualTo(201);
  }

  private MockHttpServletResponse headManifest(
      final Repo repo, final String reference, final String token) throws Exception {
    return this.mockMvc
        .perform(
            head("/v2/{repo}/{name}/manifests/{reference}", repo.getName(), CHART, reference)
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse getManifest(
      final Repo repo, final String reference, final String token) throws Exception {
    return this.mockMvc
        .perform(
            get("/v2/{repo}/{name}/manifests/{reference}", repo.getName(), CHART, reference)
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  // ----
  // Tests
  // ----

  @Test
  @DisplayName("HEAD by tag is 200 with the same headers a GET of the same tag answers")
  void headByTagMirrorsGet() throws Exception {
    final var repo = this.helmRepo();
    final var token = this.adminProtocolBearerToken();
    this.pushOci(repo, "1.0.0", chart(CHART, "1.0.0"), token);

    final var head = this.headManifest(repo, "1.0.0", token);
    final var get = this.getManifest(repo, "1.0.0", token);

    assertThat(head.getStatus()).isEqualTo(200);
    assertThat(head.getHeader("Docker-Content-Digest")).isNotEmpty();
    assertThat(head.getContentType()).contains("application/vnd.oci");
    assertThat(head.getContentAsByteArray()).isEmpty();

    assertThat(head.getStatus()).isEqualTo(get.getStatus());
    assertThat(head.getHeader("Docker-Content-Digest"))
        .isEqualTo(get.getHeader("Docker-Content-Digest"));
    assertThat(head.getContentType()).isEqualTo(get.getContentType());
    assertThat(head.getHeader("Content-Length")).isEqualTo(get.getHeader("Content-Length"));
  }

  @Test
  @DisplayName(
      "HEAD by digest is 200 with the same headers a GET of the same digest answers -- the"
          + " RPS-2089 regression pin")
  void headByDigestMirrorsGet() throws Exception {
    final var repo = this.helmRepo();
    final var token = this.adminProtocolBearerToken();

    // Push manifest by tag first
    final var chartBytes = chart(CHART, "1.0.0");
    final var layerDigest = digest("SHA-256", chartBytes);
    final var upload = this.uploadBlob(repo, chartBytes, layerDigest, token);
    assertThat(upload.getStatus()).isEqualTo(201);

    final var config = "{}".getBytes(StandardCharsets.UTF_8);
    final var manifestJson =
        ("{\"schemaVersion\":2,\"mediaType\":\"%s\",\"config\":{\"mediaType\":\"%s\","
                + "\"digest\":\"%s\",\"size\":%d},\"layers\":[{\"mediaType\":\"%s\","
                + "\"digest\":\"%s\",\"size\":%d}]}")
            .formatted(
                OCI_MANIFEST_TYPE,
                OCI_CONFIG_TYPE,
                digest("SHA-256", config),
                config.length,
                OCI_LAYER_TYPE,
                layerDigest,
                chartBytes.length);

    final var pushed = this.putManifest(repo, "1.0.0", manifestJson, token);
    assertThat(pushed.getStatus()).isEqualTo(201);

    // Get the digest from the manifest
    final var manifestDigest = "sha256:" + digest("SHA-256", manifestJson.getBytes(StandardCharsets.UTF_8)).substring(7);

    final var head = this.headManifest(repo, manifestDigest, token);
    final var get = this.getManifest(repo, manifestDigest, token);

    assertThat(head.getStatus()).as(head.getContentAsString()).isEqualTo(200);
    assertThat(head.getHeader("Docker-Content-Digest")).isEqualTo(manifestDigest);
    assertThat(head.getContentType()).contains("application/vnd.oci");
    assertThat(head.getContentAsByteArray()).isEmpty();

    assertThat(head.getStatus()).isEqualTo(get.getStatus());
    assertThat(head.getHeader("Docker-Content-Digest"))
        .isEqualTo(get.getHeader("Docker-Content-Digest"));
    assertThat(head.getContentType()).isEqualTo(get.getContentType());
    assertThat(head.getHeader("Content-Length")).isEqualTo(get.getHeader("Content-Length"));
  }

  @Test
  @DisplayName("HEAD of an unknown digest or an unknown tag is 404")
  void headOfUnknownReferenceIs404() throws Exception {
    final var repo = this.helmRepo();
    final var token = this.adminProtocolBearerToken();
    this.pushOci(repo, "1.0.0", chart(CHART, "1.0.0"), token);

    final var unknownDigest = this.headManifest(repo, "sha256:" + "0".repeat(64), token);
    final var unknownTag = this.headManifest(repo, "no-such-tag", token);

    assertThat(unknownDigest.getStatus()).isEqualTo(404);
    assertThat(unknownTag.getStatus()).isEqualTo(404);
  }

  @Test
  @DisplayName(
      "HEAD of a private repo without credentials is 401 with the OCI challenge, and with"
          + " credentials is 200")
  void privateRepoIsAuthGatedWithOciChallenge() throws Exception {
    final var repo = this.privateHelmRepo();
    final var token = this.adminProtocolBearerToken();
    this.pushOci(repo, "1.0.0", chart(CHART, "1.0.0"), token);

    final var withoutCreds = this.mockMvc
        .perform(
            head("/v2/{repo}/{name}/manifests/{reference}", repo.getName(), CHART, "1.0.0")
                .with(protocolPort()))
        .andReturn()
        .getResponse();

    assertThat(withoutCreds.getStatus()).isEqualTo(401);
    // The OCI challenge is present in the WWW-Authenticate header
    final var wwwAuthenticate = withoutCreds.getHeader("WWW-Authenticate");
    assertThat(wwwAuthenticate).isNotEmpty();

    final var withCreds = this.headManifest(repo, "1.0.0", token);
    assertThat(withCreds.getStatus()).isEqualTo(200);
  }
}
