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
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.versionOfLength;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.helm.shared.utils.HelmConstants;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

/**
 * RPS-1072: chart and OCI metadata longer than its {@code helm_chart}, {@code helm_chart_version},
 * {@code helm_oci_manifest} or {@code helm_oci_blob} column used to fail the row insert with a
 * generic 400 that named no field, after the classic upload or the OCI blob had already been
 * written. Each value is now held to its column before anything is written, and refused with a 400
 * that names the field. Nothing is cut or dropped: every one of them identifies or selects the
 * chart, except the media type of a blob, which falls back to the generic one.
 *
 * <p>None of these reaches the database with an over-long value, so they run in this class's test
 * transaction.
 */
@DisplayName("Helm push holds chart and OCI metadata to its columns (RPS-1072)")
class HelmPushMetadataLengthIT extends AbstractIntegrationTest {

  private static final String CHART = "payments";
  private static final String OCTET_STREAM = "application/octet-stream";

  /**
   * {@code @Async}, so it cannot see this class's uncommitted rows; the same mock as the other Helm
   * ITs.
   */
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

  // ---------------------------------------------------------------------------------------------
  // Requests
  // ---------------------------------------------------------------------------------------------

  private ResultActions upload(final Repo repo, final byte[] chart) throws Exception {
    return this.mockMvc.perform(
        multipart(UPLOAD_PATH, repo.getName())
            .part(new MockPart("chart", "chart.tgz", chart))
            .header(AUTHORIZATION, this.adminProtocolBearerToken())
            .with(protocolPort()));
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

  /** Starts a blob upload and finalizes it with {@code bytes}; answers the finalizing request. */
  private MockHttpServletResponse uploadBlob(
      final Repo repo, final byte[] bytes, final String digest, final String contentType)
      throws Exception {
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
                .contentType(contentType)
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
      final String mediaType,
      final String manifest)
      throws Exception {
    return this.mockMvc
        .perform(
            put("/v2/{repo}/{name}/manifests/{reference}", repo.getName(), name, reference)
                .contentType(mediaType)
                .content(manifest)
                .header(AUTHORIZATION, this.adminProtocolBearerToken())
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  /** Uploads {@code chart} as the layer, then puts a manifest for it. */
  private MockHttpServletResponse pushOci(
      final Repo repo,
      final String name,
      final String reference,
      final String mediaType,
      final byte[] chart)
      throws Exception {
    final var layerDigest = digest("SHA-256", chart);
    final var upload = this.uploadBlob(repo, chart, layerDigest, OCTET_STREAM);
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
                chart.length);

    return this.putManifest(repo, name, reference, mediaType, manifest);
  }

  // ---------------------------------------------------------------------------------------------
  // What is stored
  // ---------------------------------------------------------------------------------------------

  private int count(final String table, final Repo repo) {
    this.entityManager.flush();
    final var count =
        this.jdbcTemplate.queryForObject(
            "select count(*) from %s where repo_id = ?".formatted(table),
            Integer.class,
            repo.getId());

    return count == null ? 0 : count;
  }

  private Map<String, Object> storedVersion(final Repo repo) {
    this.entityManager.flush();

    return this.jdbcTemplate.queryForMap(
        """
        select c.name, v.version, v.app_version, v.type
          from helm_chart_version v join helm_chart c on c.id = v.chart_id
          where c.repo_id = ?
        """,
        repo.getId());
  }

  private Map<String, Object> storedManifest(final Repo repo) {
    this.entityManager.flush();

    return this.jdbcTemplate.queryForMap(
        "select name, reference, media_type from helm_oci_manifest where repo_id = ?",
        repo.getId());
  }

  private List<String> storedFiles(final Repo repo) throws IOException {
    final var dir = storageDirOf(repo);
    if (!Files.exists(dir)) {
      return List.of();
    }
    try (final var files = Files.walk(dir)) {
      return files
          .filter(Files::isRegularFile)
          .map(file -> dir.relativize(file).toString())
          .sorted()
          .toList();
    }
  }

  private static void expectOciError(
      final MockHttpServletResponse response,
      final String code,
      final String msgId,
      final String text)
      throws Exception {
    final var body = response.getContentAsString(StandardCharsets.UTF_8);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(JsonPath.<String>read(body, "$.errors[0].code")).isEqualTo(code);
    assertThat(JsonPath.<String>read(body, "$.errors[0].message")).isEqualTo(text);
    assertThat(JsonPath.<String>read(body, "$.errors[0].detail")).isEqualTo(msgId);
  }

  // ---------------------------------------------------------------------------------------------
  // POST /{repo}/api/charts
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("chart upload, POST /{repo}/api/charts")
  class ClassicUpload {

    static Stream<Arguments> overLongCharts() {
      return Stream.of(
          Arguments.of(
              "name",
              chart("n".repeat(256), "1.0.0"),
              "chartNameTooLong",
              "The chart name is longer than 255 characters."),
          Arguments.of(
              "version",
              chart(CHART, versionOfLength(HelmConstants.MAX_CHART_VERSION_LENGTH + 1)),
              "chartVersionTooLong",
              "The chart version is longer than 64 characters."),
          Arguments.of(
              "appVersion",
              chart(
                  CHART, "1.0.0", "a".repeat(HelmConstants.MAX_CHART_APP_VERSION_LENGTH + 1), null),
              "chartAppVersionTooLong",
              "The chart appVersion is longer than 64 characters."),
          Arguments.of(
              "type",
              chart(CHART, "1.0.0", null, "t".repeat(HelmConstants.MAX_CHART_TYPE_LENGTH + 1)),
              "chartTypeInvalid",
              "Invalid chart type: it must be a string of at most 32 characters."));
    }

    @Test
    @DisplayName("stores a version, appVersion and type of exactly their column lengths")
    void storesValuesAtTheLimit() throws Exception {
      final var it = HelmPushMetadataLengthIT.this;
      final var repo = it.helmRepo();
      final var version = versionOfLength(HelmConstants.MAX_CHART_VERSION_LENGTH);
      final var appVersion = "a".repeat(HelmConstants.MAX_CHART_APP_VERSION_LENGTH);
      final var type = "t".repeat(HelmConstants.MAX_CHART_TYPE_LENGTH);

      it.upload(repo, chart(CHART, version, appVersion, type)).andExpect(status().isCreated());

      assertThat(it.storedVersion(repo))
          .containsEntry("name", CHART)
          .containsEntry("version", version)
          .containsEntry("app_version", appVersion)
          .containsEntry("type", type);
      assertThat(it.indexYaml(repo)).contains(version);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("overLongCharts")
    @DisplayName("refuses a value over its column with a 400 that names it and stores nothing")
    void refusesOverLongValue(
        final String field, final byte[] chart, final String msgId, final String text)
        throws Exception {
      final var it = HelmPushMetadataLengthIT.this;
      final var repo = it.helmRepo();

      expectError(it.upload(repo, chart), HttpStatus.BAD_REQUEST, msgId, msgId, text);

      assertThat(it.count("helm_chart", repo)).as("no chart row").isZero();
      assertThat(it.storedFiles(repo)).as("no chart file").isEmpty();
      assertThat(it.indexYaml(repo)).as("not in the index").doesNotContain("aaaa");
    }

    @Test
    @DisplayName("refuses an over-long value on an overridable repo and keeps the stored version")
    void keepsTheStoredVersionOnOverride() throws Exception {
      final var it = HelmPushMetadataLengthIT.this;
      final var repo = it.overridableHelmRepo();
      it.upload(repo, chart(CHART, "1.0.0", "1.0", "application")).andExpect(status().isCreated());
      final var before = it.storedVersion(repo);
      final var files = it.storedFiles(repo);

      expectError(
          it.upload(repo, chart(CHART, "1.0.0", "a".repeat(65), null)),
          HttpStatus.BAD_REQUEST,
          "chartAppVersionTooLong",
          "chartAppVersionTooLong",
          "The chart appVersion is longer than 64 characters.");

      assertThat(it.storedVersion(repo)).as("the stored version is untouched").isEqualTo(before);
      assertThat(it.storedFiles(repo)).isEqualTo(files);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // PUT /v2/{repo}/{name}/manifests/{reference}
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("OCI manifest push")
  class OciManifestPush {

    private static final String LONG_NAME = "n".repeat(HelmConstants.MAX_OCI_MANIFEST_NAME_LENGTH);
    private static final String LONG_REFERENCE =
        "r".repeat(HelmConstants.MAX_OCI_MANIFEST_REFERENCE_LENGTH);
    private static final String LONG_TYPE =
        "application/"
            + "x".repeat(HelmConstants.MAX_OCI_MEDIA_TYPE_LENGTH - "application/".length());

    @Test
    @DisplayName("stores a name, reference and Content-Type of exactly their column lengths")
    void storesValuesAtTheLimit() throws Exception {
      final var it = HelmPushMetadataLengthIT.this;
      final var repo = it.helmRepo();

      final var push =
          it.pushOci(repo, LONG_NAME, LONG_REFERENCE, LONG_TYPE, chart(LONG_NAME, "1.0.0"));

      assertThat(push.getStatus()).as(push.getContentAsString()).isEqualTo(201);
      assertThat(it.storedManifest(repo))
          .containsEntry("name", LONG_NAME)
          .containsEntry("reference", LONG_REFERENCE)
          .containsEntry("media_type", LONG_TYPE);
      assertThat(it.storedVersion(repo)).containsEntry("name", LONG_NAME);
    }

    @Test
    @DisplayName("refuses a name over 255 characters with a NAME_INVALID that names it")
    void refusesOverLongName() throws Exception {
      final var it = HelmPushMetadataLengthIT.this;
      final var repo = it.helmRepo();

      final var push = it.putManifest(repo, LONG_NAME + "n", "1.0.0", OCI_MANIFEST_TYPE, "{}");

      expectOciError(
          push,
          "NAME_INVALID",
          "manifestNameTooLong",
          "The name in the manifest path is longer than 255 characters.");
      it.expectNothingStored(repo);
    }

    @Test
    @DisplayName("refuses a reference over 255 characters with a MANIFEST_INVALID that names it")
    void refusesOverLongReference() throws Exception {
      final var it = HelmPushMetadataLengthIT.this;
      final var repo = it.helmRepo();

      final var push = it.putManifest(repo, CHART, LONG_REFERENCE + "r", OCI_MANIFEST_TYPE, "{}");

      expectOciError(
          push,
          "MANIFEST_INVALID",
          "manifestReferenceTooLong",
          "The reference in the manifest path is longer than 255 characters.");
      it.expectNothingStored(repo);
    }

    @Test
    @DisplayName("refuses a Content-Type over 255 characters with a MANIFEST_INVALID that names it")
    void refusesOverLongMediaType() throws Exception {
      final var it = HelmPushMetadataLengthIT.this;
      final var repo = it.helmRepo();

      final var push = it.putManifest(repo, CHART, "1.0.0", LONG_TYPE + "x", "{}");

      expectOciError(
          push,
          "MANIFEST_INVALID",
          "manifestMediaTypeTooLong",
          "The Content-Type of the manifest is longer than 255 characters.");
      it.expectNothingStored(repo);
    }

    @Test
    @DisplayName("refuses a chart version over 64 characters and stores no chart or manifest")
    void refusesOverLongChartVersion() throws Exception {
      final var it = HelmPushMetadataLengthIT.this;
      final var repo = it.helmRepo();
      final var version = versionOfLength(HelmConstants.MAX_CHART_VERSION_LENGTH + 1);

      final var push = it.pushOci(repo, CHART, "1.0.0", OCI_MANIFEST_TYPE, chart(CHART, version));

      expectOciError(
          push,
          "MANIFEST_INVALID",
          "chartVersionTooLong",
          "The chart version is longer than 64 characters.");
      assertThat(it.count("helm_chart", repo)).isZero();
      assertThat(it.count("helm_oci_manifest", repo)).isZero();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // PUT /v2/{repo}/{name}/blobs/uploads/{id}
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("OCI blob upload")
  class OciBlobUpload {

    private final byte[] blob = "a blob".getBytes(StandardCharsets.UTF_8);

    private String storedMediaType(final Repo repo) {
      final var it = HelmPushMetadataLengthIT.this;

      it.entityManager.flush();

      return it.jdbcTemplate.queryForObject(
          "select media_type from helm_oci_blob where repo_id = ?", String.class, repo.getId());
    }

    @Test
    @DisplayName("refuses a sha512 digest, which does not fit the column, and stores nothing")
    void refusesSha512() throws Exception {
      final var it = HelmPushMetadataLengthIT.this;
      final var repo = it.helmRepo();

      final var response =
          it.uploadBlob(repo, this.blob, digest("SHA-512", this.blob), OCTET_STREAM);

      expectOciError(
          response,
          "DIGEST_INVALID",
          "blobDigestUnsupported",
          "Only sha256 digests are supported for blobs.");
      assertThat(it.count("helm_oci_blob", repo)).as("no blob row").isZero();
      assertThat(it.storedFiles(repo)).as("no blob file").isEmpty();
    }

    @Test
    @DisplayName("still stores a sha256 digest, which is exactly as long as the column")
    void storesSha256() throws Exception {
      final var it = HelmPushMetadataLengthIT.this;
      final var repo = it.helmRepo();
      final var digest = digest("SHA-256", this.blob);

      final var response = it.uploadBlob(repo, this.blob, digest, OCTET_STREAM);

      assertThat(response.getStatus()).isEqualTo(201);
      assertThat(digest).hasSize(HelmConstants.MAX_DIGEST_LENGTH);
      assertThat(it.count("helm_oci_blob", repo)).isEqualTo(1);
    }

    @Test
    @DisplayName("records a Content-Type of exactly 255 characters as it is")
    void keepsAMediaTypeAtTheLimit() throws Exception {
      final var it = HelmPushMetadataLengthIT.this;
      final var repo = it.helmRepo();
      final var mediaType =
          "application/"
              + "x".repeat(HelmConstants.MAX_OCI_MEDIA_TYPE_LENGTH - "application/".length());

      final var response = it.uploadBlob(repo, this.blob, digest("SHA-256", this.blob), mediaType);

      assertThat(response.getStatus()).isEqualTo(201);
      assertThat(this.storedMediaType(repo)).isEqualTo(mediaType);
    }

    @Test
    @DisplayName("records the generic type for a Content-Type over 255 characters")
    void fallsBackForAnOverLongMediaType() throws Exception {
      final var it = HelmPushMetadataLengthIT.this;
      final var repo = it.helmRepo();
      final var mediaType = "application/" + "x".repeat(HelmConstants.MAX_OCI_MEDIA_TYPE_LENGTH);

      final var response = it.uploadBlob(repo, this.blob, digest("SHA-256", this.blob), mediaType);

      assertThat(response.getStatus()).isEqualTo(201);
      assertThat(this.storedMediaType(repo)).isEqualTo(OCTET_STREAM);
    }
  }

  private void expectNothingStored(final Repo repo) throws IOException {
    assertThat(this.count("helm_chart", repo)).as("no chart row").isZero();
    assertThat(this.count("helm_oci_manifest", repo)).as("no manifest row").isZero();
    assertThat(this.storedFiles(repo)).as("no file").isEmpty();
  }
}
