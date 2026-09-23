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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * RPS-1217: a chart published only through the OCI route was listed in {@code index.yaml} but 404ed
 * on the classic download route, because an OCI push writes the archive only under {@code
 * oci/blobs/<digest>}, never under {@code charts/}. {@code getChart} now falls back to the OCI blob
 * the same way {@code deleteChartFile} already did.
 *
 * <p>Depends on RPS-1218 landing first: the fallback serves {@code oci/blobs/<chart row's digest>},
 * so if an override left that digest stale, this would serve superseded bytes instead of an honest
 * 404. Test (1) below, which hashes the downloaded bytes against the digest the index advertised,
 * is what would catch that ordering violation.
 */
@DisplayName("Helm classic download serves an OCI-only chart (RPS-1217)")
class HelmOciClassicDownloadIT extends AbstractIntegrationTest {

  private static final String CHART = "payments";
  private static final String OCTET_STREAM = "application/octet-stream";

  /** {@code @Async}, so it cannot see this class's uncommitted rows. */
  @MockitoBean private UsageUpdateService usageUpdateService;

  private Repo helmRepo() {
    return this.seedRepo(RepoType.HELM, uniqueRepoName("helm"));
  }

  // ---------------------------------------------------------------------------------------------
  // Requests
  // ---------------------------------------------------------------------------------------------

  private MockHttpServletResponse uploadBlob(
      final Repo repo, final byte[] bytes, final String digest) throws Exception {
    final var token = this.adminProtocolBearerToken();
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
      final Repo repo, final String name, final String reference, final String manifest)
      throws Exception {
    return this.mockMvc
        .perform(
            put("/v2/{repo}/{name}/manifests/{reference}", repo.getName(), name, reference)
                .contentType(OCI_MANIFEST_TYPE)
                .content(manifest)
                .header(AUTHORIZATION, this.adminProtocolBearerToken())
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  /**
   * Uploads {@code chartBytes} as the layer, then puts a manifest for it; answers the digest used.
   */
  private String pushOci(
      final Repo repo, final String name, final String reference, final byte[] chartBytes)
      throws Exception {
    final var layerDigest = digest("SHA-256", chartBytes);
    final var upload = this.uploadBlob(repo, chartBytes, layerDigest);
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

    final var pushed = this.putManifest(repo, name, reference, manifest);
    assertThat(pushed.getStatus()).as(pushed.getContentAsString()).isEqualTo(201);
    return layerDigest;
  }

  private MockHttpServletResponse uploadClassic(final Repo repo, final byte[] chartBytes)
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

  private MockHttpServletResponse indexYaml(final Repo repo) throws Exception {
    return this.mockMvc
        .perform(
            get("/{repo}/index.yaml", repo.getName())
                .header(AUTHORIZATION, this.adminProtocolBearerToken())
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse downloadClassic(final Repo repo, final String filename)
      throws Exception {
    return this.mockMvc
        .perform(
            get("/{repo}/charts/{filename}", repo.getName(), filename)
                .header(AUTHORIZATION, this.adminProtocolBearerToken())
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse deleteVersion(
      final Repo repo, final String name, final String version) throws Exception {
    return this.mockMvc
        .perform(
            delete("/{repo}/api/charts/{name}/{version}", repo.getName(), name, version)
                .header(AUTHORIZATION, this.adminProtocolBearerToken())
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private static String sha256Of(final byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "a chart published only via OCI is listed in index.yaml and its advertised URL downloads"
          + " the exact bytes the index's digest names (regression pin)")
  void ociOnlyChartDownloadsViaTheAdvertisedClassicUrl() throws Exception {
    final var repo = this.helmRepo();
    final var chartBytes = chart(CHART, "1.0.0");

    final var layerDigest = this.pushOci(repo, CHART, "1.0.0", chartBytes);

    final var index = this.indexYaml(repo);
    final var indexBody = index.getContentAsString(StandardCharsets.UTF_8);
    assertThat(indexBody).contains("payments-1.0.0.tgz").contains(layerDigest);

    final var download = this.downloadClassic(repo, CHART + "-1.0.0.tgz");

    assertThat(download.getStatus()).as(download.getContentAsString()).isEqualTo(200);
    final var downloadedBytes = download.getContentAsByteArray();
    assertThat(sha256Of(downloadedBytes))
        .as("the served bytes hash to the digest index.yaml advertised")
        .isEqualTo(layerDigest);
    assertThat(downloadedBytes).isEqualTo(chartBytes);
  }

  @Test
  @DisplayName("a chart published only via the classic route still downloads, byte-identical")
  void classicOnlyChartStillDownloads() throws Exception {
    final var repo = this.helmRepo();
    final var chartBytes = chart(CHART, "2.0.0");

    assertThat(this.uploadClassic(repo, chartBytes).getStatus()).isEqualTo(201);

    final var download = this.downloadClassic(repo, CHART + "-2.0.0.tgz");

    assertThat(download.getStatus()).isEqualTo(200);
    assertThat(download.getContentAsByteArray()).isEqualTo(chartBytes);
  }

  @Test
  @DisplayName("deleting an OCI-only chart version makes its classic download 404 again")
  void deletedOciOnlyChartStops404ingCorrectly() throws Exception {
    final var repo = this.helmRepo();
    final var chartBytes = chart(CHART, "3.0.0");
    this.pushOci(repo, CHART, "3.0.0", chartBytes);
    assertThat(this.downloadClassic(repo, CHART + "-3.0.0.tgz").getStatus()).isEqualTo(200);

    final var deleted = this.deleteVersion(repo, CHART, "3.0.0");
    assertThat(deleted.getStatus()).as(deleted.getContentAsString()).isEqualTo(200);

    final var response = this.downloadClassic(repo, CHART + "-3.0.0.tgz");
    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(JsonPath.<String>read(response.getContentAsString(), "$.data"))
        .isEqualTo("chartNotFound");
  }

  @Test
  @DisplayName("a nonexistent chart file 404s with chartNotFound")
  void nonexistentChartFile404s() throws Exception {
    final var repo = this.helmRepo();

    final var response = this.downloadClassic(repo, "does-not-exist-1.0.0.tgz");

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(JsonPath.<String>read(response.getContentAsString(), "$.data"))
        .isEqualTo("chartNotFound");
  }
}
