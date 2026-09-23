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
 * RPS-1111: {@code HelmOciManifestService.save}'s update branch refreshed a manifest row's {@code
 * digest}/{@code mediaType}/{@code content} on an override but left {@code chartVersion} pointing
 * at whichever chart version first created the row. When a repo allows override and an OCI tag (for
 * example {@code latest}) is pushed again for a different chart version -- something a real OCI
 * client can do even though {@code helm push} itself always tags with the version -- the manifest
 * row kept describing the new content while staying linked to the old version. Deleting that old
 * version then cascade-deleted the manifest row (the FK is {@code on delete cascade}), taking the
 * tag down with it even though it now belonged to a version that still exists.
 */
@DisplayName("Helm OCI tag re-link on override (RPS-1111)")
class HelmOciTagRelinkIT extends AbstractIntegrationTest {

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

  // ---------------------------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("re-pushing an existing tag for a different chart version re-links the manifest row")
  void repushingATagRelinksTheManifestRow() throws Exception {
    final var repo = this.overridableHelmRepo();
    final var token = this.asProtocolBearer(this.adminBearerToken());

    assertThat(this.pushOci(repo, CHART, LATEST, chart(CHART, "1.0.0"), token).getStatus())
        .isEqualTo(201);
    assertThat(this.linkedVersion(repo, CHART, LATEST)).isEqualTo("1.0.0");

    assertThat(this.pushOci(repo, CHART, LATEST, chart(CHART, "2.0.0"), token).getStatus())
        .isEqualTo(201);

    assertThat(this.linkedVersion(repo, CHART, LATEST))
        .as("the row must follow the tag to the new chart version, not stay on the old one")
        .isEqualTo("2.0.0");
  }

  @Test
  @DisplayName(
      "deleting the old version the tag used to point at leaves the re-linked tag resolving to"
          + " the new version, still listed among its tags")
  void relinkedTagSurvivesDeletionOfTheOldVersion() throws Exception {
    final var repo = this.overridableHelmRepo();
    final var panelToken = this.adminBearerToken();
    final var token = this.asProtocolBearer(panelToken);

    assertThat(this.pushOci(repo, CHART, LATEST, chart(CHART, "1.0.0"), token).getStatus())
        .isEqualTo(201);

    final var second = this.pushOci(repo, CHART, LATEST, chart(CHART, "2.0.0"), token);
    assertThat(second.getStatus()).as(second.getContentAsString()).isEqualTo(201);
    final var secondDigest = second.getHeader(DOCKER_CONTENT_DIGEST);
    assertThat(secondDigest).as("push response must echo the new manifest's digest").isNotBlank();

    final var deleted = this.deleteVersion(repo, CHART, "1.0.0", panelToken);
    assertThat(deleted.getStatus()).as(deleted.getContentAsString()).isEqualTo(200);

    final var manifest = this.getManifest(repo, CHART, LATEST, token);
    assertThat(manifest.getStatus())
        .as(
            "latest must still resolve after its old version was deleted: "
                + manifest.getContentAsString())
        .isEqualTo(200);
    assertThat(manifest.getHeader(DOCKER_CONTENT_DIGEST))
        .as("latest must still resolve to 2.0.0's digest, not 404 or a stale one")
        .isEqualTo(secondDigest);

    final var tags = this.tagsList(repo, CHART, token);
    assertThat(tags.getStatus()).isEqualTo(200);
    final List<String> tagNames = JsonPath.read(tags.getContentAsString(), "$.tags");
    assertThat(tagNames).as("2.0.0's own tags must still include latest").contains(LATEST);
  }
}
