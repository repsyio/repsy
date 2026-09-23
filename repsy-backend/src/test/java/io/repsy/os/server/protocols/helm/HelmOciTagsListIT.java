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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * RPS-1219: there was no handler for {@code GET /v2/{repo}/{name}/tags/list}, so a real Helm client
 * pull without an exact {@code --version} (or with a semver constraint) failed: Helm's own {@code
 * ValidateReference} calls {@code Tags(...)} in both cases. The route now exists, reusing the
 * previously dead-code {@code HelmFacade.listTags}, filtered to tags only (no {@code sha256:...}
 * digest references) and lexically sorted, per the distribution spec.
 */
@DisplayName("Helm OCI tags/list (RPS-1219)")
class HelmOciTagsListIT extends AbstractIntegrationTest {

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

  // ---------------------------------------------------------------------------------------------
  // Requests
  // ---------------------------------------------------------------------------------------------

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
      final String name,
      final String reference,
      final String manifest,
      final String token)
      throws Exception {
    return this.mockMvc
        .perform(
            put("/v2/{repo}/{name}/manifests/{reference}", repo.getName(), name, reference)
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
      final String name,
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

    final var pushed = this.putManifest(repo, name, reference, manifest, token);
    assertThat(pushed.getStatus()).as(pushed.getContentAsString()).isEqualTo(201);
  }

  /** Pushes a manifest addressed directly by digest, the way an OCI client may (RPS-1219). */
  private String pushOciByDigest(final Repo repo, final String name, final String token)
      throws Exception {
    final var chartBytes = chart(name, "9.9.9");
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
    final var manifestDigest = digest("SHA-256", manifestJson.getBytes(StandardCharsets.UTF_8));

    final var pushed = this.putManifest(repo, name, manifestDigest, manifestJson, token);
    assertThat(pushed.getStatus()).as(pushed.getContentAsString()).isEqualTo(201);
    return manifestDigest;
  }

  private MockHttpServletResponse tagsList(final Repo repo, final String name, final String token)
      throws Exception {
    final var request =
        get("/v2/{repo}/{name}/tags/list", repo.getName(), name).with(protocolPort());
    if (token != null) {
      request.header(AUTHORIZATION, token);
    }
    return this.mockMvc.perform(request).andReturn().getResponse();
  }

  // ---------------------------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("lists both pushed versions of a chart, lexically sorted (regression pin)")
  void listsBothPushedVersionsSorted() throws Exception {
    final var repo = this.helmRepo();
    final var token = this.adminProtocolBearerToken();
    this.pushOci(repo, CHART, "1.0.0", chart(CHART, "1.0.0"), token);
    this.pushOci(repo, CHART, "0.9.0", chart(CHART, "0.9.0"), token);

    final var response = this.tagsList(repo, CHART, token);

    assertThat(response.getStatus()).isEqualTo(200);
    final var body = response.getContentAsString(StandardCharsets.UTF_8);
    assertThat(JsonPath.<String>read(body, "$.name")).isEqualTo(CHART);
    assertThat(JsonPath.<List<String>>read(body, "$.tags")).containsExactly("0.9.0", "1.0.0");
  }

  @Test
  @DisplayName("a manifest pushed by digest does not appear in tags/list")
  void digestReferenceIsAbsent() throws Exception {
    final var repo = this.helmRepo();
    final var token = this.adminProtocolBearerToken();
    this.pushOci(repo, CHART, "1.0.0", chart(CHART, "1.0.0"), token);
    final var manifestDigest = this.pushOciByDigest(repo, CHART, token);

    final var response = this.tagsList(repo, CHART, token);

    final var body = response.getContentAsString(StandardCharsets.UTF_8);
    final List<String> tags = JsonPath.read(body, "$.tags");
    assertThat(tags).containsExactly("1.0.0");
    assertThat(tags).doesNotContain(manifestDigest);
  }

  @Test
  @DisplayName("an unknown chart answers an empty tag list, not an error")
  void unknownChartAnswersEmptyList() throws Exception {
    final var repo = this.helmRepo();
    final var token = this.adminProtocolBearerToken();

    final var response = this.tagsList(repo, "does-not-exist", token);

    assertThat(response.getStatus()).isEqualTo(200);
    final var body = response.getContentAsString(StandardCharsets.UTF_8);
    assertThat(JsonPath.<String>read(body, "$.name")).isEqualTo("does-not-exist");
    final List<String> tags = JsonPath.read(body, "$.tags");
    assertThat(tags).isEmpty();
  }

  @Test
  @DisplayName("a private repo refuses tags/list without credentials and allows it with them")
  void privateRepoIsAuthGated() throws Exception {
    final var repo = this.privateHelmRepo();
    final var token = this.adminProtocolBearerToken();
    this.pushOci(repo, CHART, "1.0.0", chart(CHART, "1.0.0"), token);

    final var withoutCreds = this.tagsList(repo, CHART, null);
    assertThat(withoutCreds.getStatus()).isEqualTo(401);

    final var withCreds = this.tagsList(repo, CHART, token);
    assertThat(withCreds.getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("the new route does not shadow the existing manifest-pull-by-exact-version route")
  void doesNotShadowManifestPull() throws Exception {
    final var repo = this.helmRepo();
    final var token = this.adminProtocolBearerToken();
    this.pushOci(repo, CHART, "1.0.0", chart(CHART, "1.0.0"), token);

    final var pull =
        this.mockMvc
            .perform(
                get("/v2/{repo}/{name}/manifests/{reference}", repo.getName(), CHART, "1.0.0")
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(pull.getStatus()).isEqualTo(200);
  }
}
