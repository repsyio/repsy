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
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.chart;
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.digest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.core.events.ArtifactPushedEvent;
import io.repsy.os.AbstractIT;
import io.repsy.os.server.security.shared.resolvers.HelmArtifactStorageResolver;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * RPS-1736: a chart published only through OCI stores its archive as the chart layer blob under
 * {@code oci/blobs/<digest>}, never under {@code charts/}, so the scan's storage resolver used to
 * find nothing for it and the scan was never submitted. It now falls back to the blob the same way
 * the classic download does (RPS-1217), and the push event names that blob.
 */
@RecordApplicationEvents
@DisplayName("Helm scan storage resolution for an OCI-only chart (RPS-1736)")
class HelmOciScanResolverIT extends AbstractIT {

  private static final String CHART = "payments";
  private static final String OCTET_STREAM = "application/octet-stream";

  /** {@code @Async}, so it cannot see this class's uncommitted rows. */
  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private HelmArtifactStorageResolver resolver;
  @Autowired private ApplicationEvents applicationEvents;

  private Repo scannedHelmRepo() {
    final var repo = this.seedRepo(RepoType.HELM, uniqueRepoName("helm"));
    final var managed = this.repoRepository.findByName(repo.getName()).orElseThrow();
    managed.setSecurityScanEnabled(true);
    this.repoRepository.saveAndFlush(managed);

    return this.reloadRepo(repo.getName());
  }

  private void pushOci(final Repo repo, final byte[] chartBytes) throws Exception {
    final var token = this.adminProtocolBearerToken();
    final var layerDigest = digest("SHA-256", chartBytes);
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
    final var upload =
        this.mockMvc
            .perform(
                put("/v2/{repo}/{name}/blobs/uploads/{id}", repo.getName(), CHART, uploadId)
                    .param("digest", layerDigest)
                    .contentType(OCTET_STREAM)
                    .content(chartBytes)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
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
    final var put =
        this.mockMvc
            .perform(
                put("/v2/{repo}/{name}/manifests/{reference}", repo.getName(), CHART, "1.0.0")
                    .contentType(OCI_MANIFEST_TYPE)
                    .content(manifest)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(put.getStatus()).as("manifest push").isEqualTo(201);
  }

  private MockHttpServletResponse pushClassic(final Repo repo, final byte[] chartBytes)
      throws Exception {
    return this.mockMvc
        .perform(
            multipart(UPLOAD_PATH, repo.getName())
                .part(new MockPart("chart", "chart.tgz", chartBytes))
                .header(AUTHORIZATION, this.adminProtocolBearerToken())
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  @Test
  @DisplayName("an OCI-only chart resolves to its chart layer blob and the push event names it")
  void ociOnlyChartResolvesToTheBlob() throws Exception {
    final var repo = this.scannedHelmRepo();
    final var bytes = chart(CHART, "1.0.0");

    this.pushOci(repo, bytes);

    final var expected = "oci/blobs/" + digest("SHA-256", bytes);
    assertThat(this.resolver.resolve(repo.getId(), repo.getName(), CHART, "1.0.0"))
        .contains(expected);
    assertThat(this.applicationEvents.stream(ArtifactPushedEvent.class))
        .filteredOn(event -> "1.0.0".equals(event.artifactVersion()))
        .extracting(ArtifactPushedEvent::storagePath)
        .contains(expected);
  }

  @Test
  @DisplayName("a classic chart still resolves to charts/<name>-<version>.tgz")
  void classicChartKeepsItsPath() throws Exception {
    final var repo = this.scannedHelmRepo();

    assertThat(this.pushClassic(repo, chart(CHART, "1.0.0")).getStatus()).isEqualTo(201);

    assertThat(this.resolver.resolve(repo.getId(), repo.getName(), CHART, "1.0.0"))
        .contains("charts/payments-1.0.0.tgz");
  }

  @Test
  @DisplayName("a chart that was never published resolves to nothing")
  void missingChartResolvesToNothing() {
    final var repo = this.scannedHelmRepo();

    assertThat(this.resolver.resolve(repo.getId(), repo.getName(), CHART, "9.9.9")).isEmpty();
  }
}
