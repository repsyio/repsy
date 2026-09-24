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
import static io.repsy.protocols.helm.shared.utils.HelmOciHttpValues.DOCKER_CONTENT_DIGEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * RPS-1314 (investigation): Docker RPS-1216 removed the "one manifest per tag" shape, where an
 * override of a tag lost the manifest it replaced. Helm OCI keeps one {@code helm_oci_manifest} row
 * per {@code (name, reference)}, and a real client ({@code helm push}) sends two requests, one by
 * the manifest's digest and one by the tag. The override moves the tag's row; the row of the
 * earlier digest is a row of its own and stays, so the previous digest is still pullable. This pins
 * that.
 */
@DisplayName("Helm OCI override keeps the previous digest pullable (RPS-1314)")
class HelmOciOverrideDigestIT extends AbstractIntegrationTest {

  private static final String CHART = "payments";
  private static final String OCTET_STREAM = "application/octet-stream";
  private static final String LATEST = "latest";

  /** {@code @Async}, so it cannot see this class's uncommitted rows. */
  @MockitoBean private UsageUpdateService usageUpdateService;

  private Repo overridableHelmRepo() {
    final var repo = this.seedRepo(RepoType.HELM, uniqueRepoName("helm"));
    final var managed = this.repoRepository.findByName(repo.getName()).orElseThrow();

    managed.setAllowOverride(true);
    this.repoRepository.saveAndFlush(managed);

    return this.reloadRepo(repo.getName());
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
   * Uploads {@code chartBytes} as the layer, then PUTs a manifest for it under {@code reference}
   * (independent of the version the archive's own {@code Chart.yaml} carries -- exactly how a real
   * OCI client, unlike {@code helm push} itself, can push an existing tag for a new chart version).
   */
  private MockHttpServletResponse pushOci(
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

    return this.putManifest(repo, name, reference, manifest, token);
  }

  private MockHttpServletResponse getManifest(
      final Repo repo, final String name, final String reference, final String token)
      throws Exception {
    return this.mockMvc
        .perform(
            get("/v2/{repo}/{name}/manifests/{reference}", repo.getName(), name, reference)
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse tagsList(final Repo repo, final String name, final String token)
      throws Exception {
    return this.mockMvc
        .perform(
            get("/v2/{repo}/{name}/tags/list", repo.getName(), name)
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse deleteVersion(
      final Repo repo, final String name, final String version, final String panelToken)
      throws Exception {
    return this.perform(
            delete("/api/helm/charts/{repo}/{name}/{version}", repo.getName(), name, version)
                .header(AUTHORIZATION, panelToken))
        .andReturn()
        .getResponse();
  }

  /** The chart version the manifest row named {@code (name, reference)} is currently linked to. */
  private String linkedVersion(final Repo repo, final String name, final String reference) {
    this.entityManager.flush();

    return this.jdbcTemplate.queryForObject(
        """
        select v.version
          from helm_oci_manifest m join helm_chart_version v on v.id = m.chart_version_id
         where m.repo_id = ? and m.name = ? and m.reference = ?
        """,
        String.class,
        repo.getId(),
        name,
        reference);
  }

  /** As {@code helm push} does: the manifest by its own digest, then by the tag. */
  private String pushLikeHelm(
      final Repo repo, final byte[] chart, final String tag, final String token) throws Exception {
    final var byTag = this.pushOci(repo, CHART, tag, chart, token);
    final var digest = byTag.getHeader(DOCKER_CONTENT_DIGEST);
    assertThat(byTag.getStatus()).as(byTag.getContentAsString()).isEqualTo(201);
    assertThat(this.pushOci(repo, CHART, digest, chart, token).getStatus()).isEqualTo(201);

    return digest;
  }

  @Test
  @DisplayName("overriding a tag moves the tag and leaves the digest it replaced pullable")
  void anOverrideKeepsThePreviousDigestPullable() throws Exception {
    final var repo = this.overridableHelmRepo();
    final var token = this.asProtocolBearer(this.adminBearerToken());
    final var before = this.pushLikeHelm(repo, chart(CHART, "1.0.0", "one", null), "1.0.0", token);

    final var after = this.pushLikeHelm(repo, chart(CHART, "1.0.0", "two", null), "1.0.0", token);

    assertThat(after).isNotEqualTo(before);
    assertThat(this.getManifest(repo, CHART, "1.0.0", token).getHeader(DOCKER_CONTENT_DIGEST))
        .as("the tag moved to the new manifest")
        .isEqualTo(after);
    assertThat(this.getManifest(repo, CHART, after, token).getStatus()).isEqualTo(200);
    final var old = this.getManifest(repo, CHART, before, token);
    assertThat(old.getStatus()).as("the replaced digest is still pullable").isEqualTo(200);
    assertThat(old.getHeader(DOCKER_CONTENT_DIGEST)).isEqualTo(before);
  }
}
