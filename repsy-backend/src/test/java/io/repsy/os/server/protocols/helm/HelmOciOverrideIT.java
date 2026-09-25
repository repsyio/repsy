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
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * RPS-1218: an accepted OCI override with different chart bytes used to leave the chart version
 * row's own {@code digest}/{@code size} stale — {@code findOrCreateVersion} returned the found row
 * untouched on an override, so {@code index.yaml} kept advertising the superseded digest even
 * though the OCI manifest row itself was refreshed. It now upserts the row the same way {@code
 * ChartService.update} already does on the classic override path.
 */
@DisplayName("Helm OCI override refreshes the chart version row (RPS-1218)")
class HelmOciOverrideIT extends AbstractIntegrationTest {

  private static final String CHART = "payments";
  private static final String OCTET_STREAM = "application/octet-stream";

  /** {@code @Async}, so it cannot see this class's uncommitted rows. */
  @MockitoBean private UsageUpdateService usageUpdateService;

  private Repo helmRepo() {
    return this.seedRepo(RepoType.HELM, uniqueRepoName("helm"));
  }

  private Repo overridableHelmRepo() {
    final var repo = this.helmRepo();
    final var managed = this.repoRepository.findByName(repo.getName()).orElseThrow();

    managed.setAllowOverride(true);
    this.repoRepository.saveAndFlush(managed);

    return this.reloadRepo(repo.getName());
  }

  /**
   * A newly created repo already has {@code allowOverride = true} by default ({@link
   * io.repsy.os.shared.repo.services.RepoTxService#createRepo}), so the "override refused" boundary
   * needs a repo that explicitly turns it off.
   */
  private Repo nonOverridableHelmRepo() {
    final var repo = this.helmRepo();
    final var managed = this.repoRepository.findByName(repo.getName()).orElseThrow();

    managed.setAllowOverride(false);
    this.repoRepository.saveAndFlush(managed);

    return this.reloadRepo(repo.getName());
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

  /** Uploads {@code chartBytes} as the layer, then puts a manifest for it; answers the response. */
  private MockHttpServletResponse pushOci(
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

    return this.putManifest(repo, name, reference, manifest);
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

  private String indexYaml(final Repo repo) throws Exception {
    return this.mockMvc
        .perform(
            get("/{repo}/index.yaml", repo.getName())
                .header(AUTHORIZATION, this.adminProtocolBearerToken())
                .with(protocolPort()))
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
  }

  private Map<String, Object> storedVersion(
      final Repo repo, final String name, final String version) {
    this.entityManager.flush();

    return this.jdbcTemplate.queryForMap(
        """
        select c.name, v.version, v.digest, v.size, v.app_version, v.type
          from helm_chart_version v join helm_chart c on c.id = v.chart_id
          where c.repo_id = ? and c.name = ? and v.version = ?
        """,
        repo.getId(),
        name,
        version);
  }

  private int versionRowCount(final Repo repo, final String name, final String version) {
    this.entityManager.flush();

    final var count =
        this.jdbcTemplate.queryForObject(
            """
            select count(*)
              from helm_chart_version v join helm_chart c on c.id = v.chart_id
              where c.repo_id = ? and c.name = ? and v.version = ?
            """,
            Integer.class,
            repo.getId(),
            name,
            version);

    return count == null ? 0 : count;
  }

  // ---------------------------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "an accepted OCI override with different bytes updates index.yaml's digest (regression pin)")
  void overrideUpdatesIndexDigest() throws Exception {
    final var repo = this.overridableHelmRepo();
    final var first = chart(CHART, "1.0.0");
    final var second = chart(CHART, "1.0.0", "2.0", "application");
    final var firstDigest = digest("SHA-256", first);
    final var secondDigest = digest("SHA-256", second);
    assertThat(firstDigest).as("the two archives must differ").isNotEqualTo(secondDigest);

    assertThat(this.pushOci(repo, CHART, "1.0.0", first).getStatus()).isEqualTo(201);
    final var override = this.pushOci(repo, CHART, "1.0.0", second);
    assertThat(override.getStatus()).as(override.getContentAsString()).isEqualTo(201);

    final var index = this.indexYaml(repo);
    assertThat(index).contains(secondDigest).doesNotContain(firstDigest);
  }

  @Test
  @DisplayName("the HelmChartVersion row itself carries the override's digest and size")
  void overrideUpdatesTheVersionRow() throws Exception {
    final var repo = this.overridableHelmRepo();
    final var first = chart(CHART, "1.0.0");
    final var second = chart(CHART, "1.0.0", "2.0", "application");
    final var secondDigest = digest("SHA-256", second);

    assertThat(this.pushOci(repo, CHART, "1.0.0", first).getStatus()).isEqualTo(201);
    assertThat(this.pushOci(repo, CHART, "1.0.0", second).getStatus()).isEqualTo(201);

    final var row = this.storedVersion(repo, CHART, "1.0.0");
    assertThat(row)
        .containsEntry("digest", secondDigest)
        .containsEntry("size", (long) second.length);
  }

  @Test
  @DisplayName("an override that changes appVersion and type propagates those too")
  void overridePropagatesAppVersionAndType() throws Exception {
    final var repo = this.overridableHelmRepo();
    final var first = chart(CHART, "1.0.0", "1.0", "library");
    final var second = chart(CHART, "1.0.0", "9.9", "application");

    assertThat(this.pushOci(repo, CHART, "1.0.0", first).getStatus()).isEqualTo(201);
    assertThat(this.pushOci(repo, CHART, "1.0.0", second).getStatus()).isEqualTo(201);

    final var row = this.storedVersion(repo, CHART, "1.0.0");
    assertThat(row).containsEntry("app_version", "9.9").containsEntry("type", "application");
  }

  @Test
  @DisplayName("re-pushing identical bytes is idempotent: digest unchanged, exactly one row")
  void identicalBytesAreIdempotent() throws Exception {
    final var repo = this.overridableHelmRepo();
    final var bytes = chart(CHART, "1.0.0");
    final var expectedDigest = digest("SHA-256", bytes);

    assertThat(this.pushOci(repo, CHART, "1.0.0", bytes).getStatus()).isEqualTo(201);
    assertThat(this.pushOci(repo, CHART, "1.0.0", bytes).getStatus()).isEqualTo(201);

    assertThat(this.versionRowCount(repo, CHART, "1.0.0")).isEqualTo(1);
    assertThat(this.storedVersion(repo, CHART, "1.0.0")).containsEntry("digest", expectedDigest);
  }

  @Test
  @DisplayName(
      "with allowOverride false, an OCI push of the same (name, version) under the same tag is"
          + " still refused with 409 chartAlreadyExists (the boundary this PR does not move), and"
          + " leaves the chart version row's digest exactly as it was")
  void allowOverrideFalseStillRefusesSameTag() throws Exception {
    final var repo = this.nonOverridableHelmRepo();
    final var first = chart(CHART, "1.0.0");
    final var second = chart(CHART, "1.0.0", "2.0", "application");
    final var firstDigest = digest("SHA-256", first);

    assertThat(this.pushOci(repo, CHART, "1.0.0", first).getStatus()).isEqualTo(201);
    final var beforeRow = this.storedVersion(repo, CHART, "1.0.0");
    final var refused = this.pushOci(repo, CHART, "1.0.0", second);

    assertThat(refused.getStatus()).isEqualTo(409);
    assertThat(JsonPath.<String>read(refused.getContentAsString(), "$.errors[0].detail"))
        .isEqualTo("chartAlreadyExists");
    // The regression pin for the exact e2e "no-override" invariant: a refused push (same name,
    // version and OCI tag/reference) must never touch the row's own digest/size, since
    // AbstractHelmOciManifestPushProtocolMethodHandler throws before ever reaching
    // findOrCreateChart/findOrCreateVersion.
    final var afterRow = this.storedVersion(repo, CHART, "1.0.0");
    assertThat(afterRow).isEqualTo(beforeRow).containsEntry("digest", firstDigest);
  }

  @Test
  @DisplayName(
      "a real client's own two-step push (by digest, then by tag) is refused as a whole: the"
          + " by-digest sub-request alone must not upsert the chart row (RPS-1218's own"
          + " findChartByNameAndVersion guard)")
  void twoStepPushIsRefusedAsAWhole() throws Exception {
    final var repo = this.nonOverridableHelmRepo();
    final var first = chart(CHART, "1.0.0");
    final var second = chart(CHART, "1.0.0", "2.0", "application");
    final var firstDigest = digest("SHA-256", first);
    final var secondDigest = digest("SHA-256", second);

    assertThat(this.pushOci(repo, CHART, "1.0.0", first).getStatus()).isEqualTo(201);
    final var beforeRow = this.storedVersion(repo, CHART, "1.0.0");

    // A real oras-go/helm OCI push does this in two separate requests: first PUT the manifest
    // under its OWN digest as the reference, THEN PUT it again under the real tag. Simulated here
    // with a sha256-shaped reference that has never been pushed before (the layer's own digest,
    // not the manifest's -- the exact digest value doesn't matter for this test, only that it is a
    // fresh reference checkManifest's by-reference lookup has never seen): never checked against
    // that refusal, since a fresh digest never already exists as its own reference -- this is the
    // sub-request that used to slip past the override check entirely and upsert the row via
    // AbstractHelmProtocolTxFacade#findOrCreateChart. The tag-referenced request below is the one
    // checkManifest's by-reference lookup DOES gate, and where the refusal is actually thrown.
    final var byDigest = this.pushOci(repo, CHART, "sha256:" + secondDigest, second);
    assertThat(byDigest.getStatus())
        .as("by-digest sub-request: " + byDigest.getContentAsString())
        .isEqualTo(409);

    final var afterByDigest = this.storedVersion(repo, CHART, "1.0.0");
    assertThat(afterByDigest)
        .as("the by-digest sub-request alone must not have touched the row")
        .isEqualTo(beforeRow)
        .containsEntry("digest", firstDigest);

    final var byTag = this.pushOci(repo, CHART, "1.0.0", second);
    assertThat(byTag.getStatus()).isEqualTo(409);

    final var afterByTag = this.storedVersion(repo, CHART, "1.0.0");
    assertThat(afterByTag).isEqualTo(beforeRow).containsEntry("digest", firstDigest);
    assertThat(this.indexYaml(repo)).contains(firstDigest).doesNotContain(secondDigest);
  }

  @Test
  @DisplayName("the classic push/override path is unchanged: it still refreshes the row and index")
  void classicOverridePathIsUnchanged() throws Exception {
    final var repo = this.overridableHelmRepo();
    final var first = chart(CHART, "1.0.0");
    final var second = chart(CHART, "1.0.0", "2.0", "application");
    final var secondDigest = digest("SHA-256", second);

    assertThat(this.uploadClassic(repo, first).getStatus()).isEqualTo(201);
    assertThat(this.uploadClassic(repo, second).getStatus()).isEqualTo(201);

    assertThat(this.storedVersion(repo, CHART, "1.0.0")).containsEntry("digest", secondDigest);
    assertThat(this.indexYaml(repo)).contains(secondDigest);
    assertThat(this.versionRowCount(repo, CHART, "1.0.0")).isEqualTo(1);
  }
}
