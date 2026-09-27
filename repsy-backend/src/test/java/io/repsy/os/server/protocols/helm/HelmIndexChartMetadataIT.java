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
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.digest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.yaml.snakeyaml.Yaml;

/**
 * RPS-1557: an {@code index.yaml} entry carried neither the {@code apiVersion} nor the {@code
 * dependencies} of the chart it lists, which ChartMuseum and {@code helm repo index} publish and
 * which tooling (Artifact Hub, {@code helm search}) reads. Both are kept with the chart version,
 * for a classic upload and an OCI push alike, and rendered per entry.
 */
@DisplayName("Helm index.yaml carries apiVersion and dependencies (RPS-1557)")
class HelmIndexChartMetadataIT extends AbstractIntegrationTest {

  private static final String CHART = "payments";
  private static final String OCTET_STREAM = "application/octet-stream";

  private static final String WITH_DEPENDENCIES =
      """
      apiVersion: v2
      name: payments
      version: "%s"
      dependencies:
        - name: postgresql
          version: "12.x.x"
          repository: https://charts.example.com/stable
          condition: postgresql.enabled
          tags: [database]
          alias: db
          enabled: true
        - name: redis
          version: "17.1.0"
          repository: "oci://registry.example.com/charts"
      """;

  /** {@code @Async}, so it cannot see this class's uncommitted rows. */
  @MockitoBean private UsageUpdateService usageUpdateService;

  private Repo helmRepo(final boolean allowOverride) {
    final var repo = this.seedRepo(RepoType.HELM, uniqueRepoName("helm"));
    if (!allowOverride) {
      return repo;
    }
    final var managed = this.repoRepository.findByName(repo.getName()).orElseThrow();
    managed.setAllowOverride(true);
    this.repoRepository.saveAndFlush(managed);

    return this.reloadRepo(repo.getName());
  }

  private void upload(final Repo repo, final String chartYaml) throws Exception {
    final var response =
        this.mockMvc
            .perform(
                multipart(UPLOAD_PATH, repo.getName())
                    .part(new MockPart("chart", "chart.tgz", archive(chartYaml)))
                    .header(AUTHORIZATION, this.adminProtocolBearerToken())
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
  }

  private void pushOci(final Repo repo, final String reference, final String chartYaml)
      throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var bytes = archive(chartYaml);
    final var layerDigest = digest("SHA-256", bytes);
    final var start =
        this.mockMvc
            .perform(
                post("/v2/{repo}/{name}/blobs/uploads/", repo.getName(), CHART)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    final var location = start.getHeader("Location");
    final var uploadId = location.substring(location.lastIndexOf('/') + 1);
    final var blob =
        this.mockMvc
            .perform(
                put("/v2/{repo}/{name}/blobs/uploads/{id}", repo.getName(), CHART, uploadId)
                    .param("digest", layerDigest)
                    .contentType(OCTET_STREAM)
                    .content(bytes)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(blob.getStatus()).as("chart layer upload").isEqualTo(201);

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
                bytes.length);
    final var pushed =
        this.mockMvc
            .perform(
                put("/v2/{repo}/{name}/manifests/{reference}", repo.getName(), CHART, reference)
                    .contentType(OCI_MANIFEST_TYPE)
                    .content(manifest)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(pushed.getStatus()).as(pushed.getContentAsString()).isEqualTo(201);
  }

  private MockHttpServletResponse rejectedUpload(final Repo repo, final String chartYaml)
      throws Exception {
    return this.mockMvc
        .perform(
            multipart(UPLOAD_PATH, repo.getName())
                .part(new MockPart("chart", "chart.tgz", archive(chartYaml)))
                .header(AUTHORIZATION, this.adminProtocolBearerToken())
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  /** The entries of {@code index.yaml} for {@code CHART}, keyed by their version. */
  @SuppressWarnings("unchecked")
  private Map<String, Map<String, Object>> entries(final Repo repo) throws Exception {
    final var body =
        this.mockMvc
            .perform(
                get("/{repo}/index.yaml", repo.getName())
                    .header(AUTHORIZATION, this.adminProtocolBearerToken())
                    .with(protocolPort()))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    final var index = (Map<String, Object>) new Yaml().load(body);
    final var chartEntries = (Map<String, List<Map<String, Object>>>) index.get("entries");

    final var byVersion = new java.util.LinkedHashMap<String, Map<String, Object>>();
    for (final var entry : chartEntries.get(CHART)) {
      byVersion.put((String) entry.get("version"), entry);
    }

    return byVersion;
  }

  @Test
  @DisplayName("a classic upload lists the apiVersion and the dependencies of Chart.yaml")
  void classicUploadIsListedWithDependencies() throws Exception {
    final var repo = this.helmRepo(false);
    this.upload(repo, WITH_DEPENDENCIES.formatted("1.0.0"));

    final var entry = this.entries(repo).get("1.0.0");

    assertThat(entry.get("apiVersion")).isEqualTo("v2");
    assertThat(entry.get("dependencies"))
        .isEqualTo(
            List.of(
                Map.of(
                    "name", "postgresql",
                    "version", "12.x.x",
                    "repository", "https://charts.example.com/stable",
                    "condition", "postgresql.enabled",
                    "tags", List.of("database"),
                    "alias", "db",
                    "enabled", true),
                Map.of(
                    "name", "redis",
                    "version", "17.1.0",
                    "repository", "oci://registry.example.com/charts")));
  }

  @Test
  @DisplayName("an OCI push lists the same apiVersion and dependencies")
  void ociPushIsListedWithDependencies() throws Exception {
    final var repo = this.helmRepo(false);
    this.pushOci(repo, "1.0.0", WITH_DEPENDENCIES.formatted("1.0.0"));

    final var entry = this.entries(repo).get("1.0.0");

    assertThat(entry.get("apiVersion")).isEqualTo("v2");
    assertThat((List<?>) entry.get("dependencies")).hasSize(2);
    assertThat(((Map<?, ?>) ((List<?>) entry.get("dependencies")).getFirst()).get("name"))
        .isEqualTo("postgresql");
  }

  @Test
  @DisplayName("a chart without dependencies has no dependencies key; without apiVersion it is v1")
  void chartWithoutDependenciesHasNoKey() throws Exception {
    final var repo = this.helmRepo(false);
    this.upload(repo, "name: payments\nversion: 1.0.0\n");
    this.upload(repo, "apiVersion: v2\nname: payments\nversion: 2.0.0\ndependencies: []\n");

    final var entries = this.entries(repo);

    assertThat(entries.get("1.0.0"))
        .containsEntry("apiVersion", "v1")
        .doesNotContainKey("dependencies");
    assertThat(entries.get("2.0.0"))
        .containsEntry("apiVersion", "v2")
        .doesNotContainKey("dependencies");
  }

  @Test
  @DisplayName("an override refreshes the dependencies of the version (classic and OCI)")
  void overrideRefreshesTheDependencies() throws Exception {
    final var repo = this.helmRepo(true);
    this.upload(repo, WITH_DEPENDENCIES.formatted("1.0.0"));
    this.pushOci(repo, "2.0.0", WITH_DEPENDENCIES.formatted("2.0.0"));

    this.upload(repo, "apiVersion: v2\nname: payments\nversion: \"1.0.0\"\ndescription: none\n");
    this.pushOci(
        repo,
        "2.0.0",
        "apiVersion: v2\nname: payments\nversion: \"2.0.0\"\ndependencies:\n  - name: kafka\n");

    final var entries = this.entries(repo);

    assertThat(entries.get("1.0.0")).doesNotContainKey("dependencies");
    assertThat(entries.get("2.0.0").get("dependencies"))
        .isEqualTo(List.of(Map.of("name", "kafka")));
  }

  @Test
  @DisplayName("a Chart.yaml with malformed dependencies or apiVersion is refused with a 400")
  void malformedDependenciesAreRefused() throws Exception {
    final var repo = this.helmRepo(false);

    final var notAList =
        this.rejectedUpload(repo, "name: payments\nversion: 1.0.0\ndependencies: redis\n");
    final var apiVersion =
        this.rejectedUpload(repo, "name: payments\nversion: 1.0.0\napiVersion: 2\n");

    assertThat(notAList.getStatus()).isEqualTo(400);
    assertThat(JsonPath.<String>read(notAList.getContentAsString(), "$.msgId"))
        .isEqualTo("chartDependenciesInvalid");
    assertThat(apiVersion.getStatus()).isEqualTo(400);
    assertThat(JsonPath.<String>read(apiVersion.getContentAsString(), "$.msgId"))
        .isEqualTo("chartApiVersionInvalid");
  }
}
