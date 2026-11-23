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
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.UPLOAD_PATH;
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.archive;
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.chart;
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.digest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIT;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The Helm registry rules over the real wire protocol that no other IT pins: the OCI code {@code
 * DENIED} of a refused override, the three Chart.yaml refusals that only had parser-level tests (no
 * Chart.yaml in the archive, a missing name, a missing version) answered as a 400 with nothing
 * stored, and {@code tags/list} after a version or the whole chart is deleted in the panel.
 */
@DisplayName("Helm registry rules (wire level)")
class HelmRegistryRulesIT extends AbstractIT {

  private static final String CHART = "payments";

  /** {@code @Async}, so it cannot see this class's uncommitted rows. */
  @MockitoBean private UsageUpdateService usageUpdateService;

  private Repo helmRepo() {
    return this.seedRepo(RepoType.HELM, uniqueRepoName("helm"));
  }

  private MockHttpServletResponse uploadClassic(final Repo repo, final byte[] archive)
      throws Exception {
    return this.mockMvc
        .perform(
            multipart(UPLOAD_PATH, repo.getName())
                .part(new MockPart("chart", "chart.tgz", archive))
                .header(AUTHORIZATION, this.adminProtocolBearerToken())
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  /** A {@code .tgz} that holds a file but no {@code Chart.yaml}. */
  private static byte[] archiveWithoutChartYaml() {
    try {
      final var bytes = new ByteArrayOutputStream();
      try (final var gzip = new GZIPOutputStream(bytes);
          final var tar = new TarArchiveOutputStream(gzip)) {
        final var data = "replicas: 1\n".getBytes(StandardCharsets.UTF_8);
        final var entry = new TarArchiveEntry("chart/values.yaml");

        entry.setSize(data.length);
        tar.putArchiveEntry(entry);
        tar.write(data);
        tar.closeArchiveEntry();
      }

      return bytes.toByteArray();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private int chartRows(final Repo repo) {
    this.entityManager.flush();

    final var count =
        this.jdbcTemplate.queryForObject(
            "select count(*) from helm_chart where repo_id = ?", Integer.class, repo.getId());

    return count == null ? 0 : count;
  }

  private MockHttpServletResponse blobUpload(
      final Repo repo, final String token, final byte[] bytes) throws Exception {
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
                .param("digest", digest("SHA-256", bytes))
                .contentType("application/octet-stream")
                .content(bytes)
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  /** Pushes {@code chartBytes} as an OCI chart under {@code tag}; answers the manifest response. */
  private MockHttpServletResponse pushOci(
      final Repo repo, final String tag, final byte[] chartBytes) throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var config = "{}".getBytes(StandardCharsets.UTF_8);

    assertThat(this.blobUpload(repo, token, chartBytes).getStatus()).isEqualTo(201);
    assertThat(this.blobUpload(repo, token, config).getStatus()).isIn(201, 202);

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
                digest("SHA-256", chartBytes),
                chartBytes.length);

    return this.mockMvc
        .perform(
            put("/v2/{repo}/{name}/manifests/{reference}", repo.getName(), CHART, tag)
                .contentType(OCI_MANIFEST_TYPE)
                .content(manifest)
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private List<String> tags(final Repo repo) throws Exception {
    final var response =
        this.mockMvc
            .perform(
                get("/v2/{repo}/{name}/tags/list", repo.getName(), CHART)
                    .header(AUTHORIZATION, this.adminProtocolBearerToken())
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(200);

    return JsonPath.read(response.getContentAsString(StandardCharsets.UTF_8), "$.tags");
  }

  @Test
  @DisplayName(
      "a refused OCI override is 409 with the OCI code DENIED and the detail chartAlreadyExists")
  void refusedOverrideCarriesTheDeniedCode() throws Exception {
    final var repo = this.helmRepo();
    final var managed = this.repoRepository.findByName(repo.getName()).orElseThrow();

    managed.setAllowOverride(false);
    this.repoRepository.saveAndFlush(managed);
    final var disabled = this.reloadRepo(repo.getName());
    assertThat(this.pushOci(disabled, "1.0.0", chart(CHART, "1.0.0")).getStatus()).isEqualTo(201);

    final var refused =
        this.pushOci(disabled, "1.0.0", chart(CHART, "1.0.0", "2.0", "application"));

    assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(409);
    assertThat(JsonPath.<String>read(refused.getContentAsString(), "$.errors[0].code"))
        .isEqualTo("DENIED");
    assertThat(JsonPath.<String>read(refused.getContentAsString(), "$.errors[0].detail"))
        .isEqualTo("chartAlreadyExists");
  }

  @Test
  @DisplayName("an archive without a Chart.yaml is a 400 chartYamlNotFound and stores nothing")
  void archiveWithoutChartYamlIsRefused() throws Exception {
    final var repo = this.helmRepo();

    final var response = this.uploadClassic(repo, archiveWithoutChartYaml());

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(400);
    assertThat(JsonPath.<String>read(response.getContentAsString(), "$.msgId"))
        .isEqualTo("chartYamlNotFound");
    assertThat(this.chartRows(repo)).isZero();
  }

  private void assertChartYamlRefused(final String chartYaml, final String msgId) throws Exception {
    final var repo = this.helmRepo();

    final var response = this.uploadClassic(repo, archive(chartYaml));

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(400);
    assertThat(JsonPath.<String>read(response.getContentAsString(), "$.msgId")).isEqualTo(msgId);
    assertThat(this.chartRows(repo)).isZero();
  }

  @Test
  @DisplayName("a Chart.yaml without a name is a 400 chartNameMissing and stores nothing")
  void missingNameIsRefused() throws Exception {
    this.assertChartYamlRefused("apiVersion: v2\nversion: 1.0.0\n", "chartNameMissing");
  }

  @Test
  @DisplayName("a Chart.yaml without a version is a 400 chartVersionMissing and stores nothing")
  void missingVersionIsRefused() throws Exception {
    this.assertChartYamlRefused("apiVersion: v2\nname: payments\n", "chartVersionMissing");
  }

  @Test
  @DisplayName("tags/list drops a deleted version, then lists nothing once the chart is deleted")
  void tagsListFollowsDeletes() throws Exception {
    final var repo = this.helmRepo();
    assertThat(this.pushOci(repo, "1.0.0", chart(CHART, "1.0.0")).getStatus()).isEqualTo(201);
    assertThat(this.pushOci(repo, "2.0.0", chart(CHART, "2.0.0")).getStatus()).isEqualTo(201);
    assertThat(this.tags(repo)).containsExactly("1.0.0", "2.0.0");

    this.perform(
            delete(
                    "/api/helm/charts/{repo}/{name}/versions/{version}",
                    repo.getName(),
                    CHART,
                    "1.0.0")
                .header(AUTHORIZATION, this.adminBearerToken()))
        .andReturn();
    assertThat(this.tags(repo)).containsExactly("2.0.0");

    this.perform(
            delete("/api/helm/charts/{repo}/{name}", repo.getName(), CHART)
                .header(AUTHORIZATION, this.adminBearerToken()))
        .andReturn();
    assertThat(this.tags(repo)).isEmpty();
  }
}
