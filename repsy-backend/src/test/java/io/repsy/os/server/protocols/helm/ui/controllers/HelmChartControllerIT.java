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
package io.repsy.os.server.protocols.helm.ui.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.jayway.jsonpath.JsonPath;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.helm.shared.chart.repositories.HelmChartRepository;
import io.repsy.os.server.protocols.helm.shared.chart.repositories.HelmChartVersionRepository;
import io.repsy.os.server.protocols.helm.shared.chart.services.HelmChartService;
import io.repsy.os.server.protocols.helm.shared.oci.services.HelmOciBlobService;
import io.repsy.os.server.protocols.helm.shared.oci.services.HelmOciManifestNameRepairService;
import io.repsy.os.server.protocols.helm.shared.oci.services.HelmOciManifestService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartForm;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestForm;
import io.repsy.protocols.helm.shared.utils.HelmConstants;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Full-stack Testcontainers coverage for the Helm chart-management API.
 *
 * <p>Fixtures are real: chart archives ({@code .tgz} with {@code Chart.yaml}, values, README and
 * templates) are uploaded through the ChartMuseum-style {@code POST /{repo}/api/charts} handler,
 * and OCI charts are pushed through the registry's blob-upload and manifest endpoints, both on the
 * protocol port. So the rows, files and usage the management API reads and deletes are exactly what
 * the wire protocols produce.
 *
 * <p>{@link UsageUpdateService} is mocked: it is {@code @Async}, so it cannot see this test's
 * uncommitted data. The mock records the disk-usage deltas the controller and the upload
 * post-processor request, which is what "repo usage updated" means here.
 */
@DisplayName("HelmChartController /api/helm/charts/*")
class HelmChartControllerIT extends AbstractIntegrationTest {

  private static final String OCI_MANIFEST_TYPE = "application/vnd.oci.image.manifest.v1+json";
  private static final String OCI_CONFIG_TYPE = "application/vnd.cncf.helm.config.v1+json";
  private static final String OCI_LAYER_TYPE =
      "application/vnd.cncf.helm.chart.content.v1.tar+gzip";
  private static final String OCI_PROVENANCE_TYPE =
      "application/vnd.cncf.helm.chart.provenance.v1.prov";
  private static final String NO_PERMISSION_TEXT = "The user has logged in but has no permissions.";
  private static final Map<String, String> SUCCESS_TEXTS =
      Map.of(
          "chartsFetched", "Charts fetched.",
          "chartVersionsFetched", "Chart versions fetched.",
          "chartDetailFetched", "Chart detail fetched.",
          "chartTagsFetched", "Chart tags fetched.",
          "chartDeleted", "Chart deleted.");

  private static final Set<String> LIST_ITEM_KEYS =
      Set.of("name", "latestVersion", "description", "type", "updatedAt");
  private static final Set<String> VERSION_ITEM_KEYS =
      Set.of("version", "appVersion", "description", "type", "digest", "size", "createdAt");
  private static final Set<String> DETAIL_KEYS =
      Set.of(
          "name",
          "version",
          "description",
          "appVersion",
          "type",
          "digest",
          "size",
          "createdAt",
          "lastUpdatedAt");

  @MockitoBean private UsageUpdateService usageUpdateService;
  @Autowired private HelmChartService helmChartService;
  @Autowired private HelmChartVersionRepository helmChartVersionRepository;
  @Autowired private HelmChartRepository helmChartRepository;
  @Autowired private HelmOciManifestService helmOciManifestService;
  @Autowired private HelmOciBlobService helmOciBlobService;
  @Autowired private HelmOciManifestNameRepairService helmOciManifestNameRepairService;

  // ---------------------------------------------------------------------------------------------
  // Fixtures: real chart archives, uploaded through the real Helm protocol handlers
  // ---------------------------------------------------------------------------------------------

  /** What goes into a generated {@code Chart.yaml}. */
  private record ChartSpec(
      String name, String version, String description, String appVersion, boolean rich) {

    static ChartSpec of(final String name, final String version) {
      return new ChartSpec(name, version, name + " chart", "1.0.0", false);
    }

    ChartSpec withDescription(final String value) {
      return new ChartSpec(this.name, this.version, value, this.appVersion, this.rich);
    }

    ChartSpec withAppVersion(final String value) {
      return new ChartSpec(this.name, this.version, this.description, value, this.rich);
    }

    /** Adds keywords, maintainers, dependencies and annotations to {@code Chart.yaml}. */
    ChartSpec withRichMetadata() {
      return new ChartSpec(this.name, this.version, this.description, this.appVersion, true);
    }

    String chartYaml() {
      final var yaml = new StringBuilder();
      yaml.append("apiVersion: v2\n");
      yaml.append("name: ").append(this.name).append('\n');
      yaml.append("version: ").append(this.version).append('\n');
      yaml.append("type: application\n");
      if (this.description != null) {
        yaml.append("description: ").append(this.description).append('\n');
      }
      if (this.appVersion != null) {
        yaml.append("appVersion: \"").append(this.appVersion).append("\"\n");
      }
      if (this.rich) {
        yaml.append(
            """
            keywords:
              - payments
              - billing
            home: https://example.test/chart
            maintainers:
              - name: Repsy
                email: charts@repsy.io
            dependencies:
              - name: postgresql
                version: 15.x.x
                repository: https://charts.example.test
            annotations:
              category: finance
            """);
      }
      return yaml.toString();
    }
  }

  /** A chart that was pushed: what went in, so tests can compare it with what comes back. */
  private record Pushed(ChartSpec spec, byte[] bytes) {

    String digest() {
      return sha256(this.bytes);
    }

    long size() {
      return this.bytes.length;
    }
  }

  private static byte[] archive(final ChartSpec spec) {
    try {
      final var bytes = new ByteArrayOutputStream();
      try (final var gzip = new GZIPOutputStream(bytes);
          final var tar = new TarArchiveOutputStream(gzip)) {
        addEntry(tar, spec.name() + "/Chart.yaml", spec.chartYaml());
        addEntry(tar, spec.name() + "/values.yaml", "replicaCount: 1\n");
        addEntry(tar, spec.name() + "/README.md", "# " + spec.name() + "\n");
        addEntry(tar, spec.name() + "/templates/NOTES.txt", "installed\n");
      }
      return bytes.toByteArray();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void addEntry(
      final TarArchiveOutputStream tar, final String path, final String content)
      throws IOException {
    final var data = content.getBytes(StandardCharsets.UTF_8);
    final var entry = new TarArchiveEntry(path);
    entry.setSize(data.length);
    tar.putArchiveEntry(entry);
    tar.write(data);
    tar.closeArchiveEntry();
  }

  private static String sha256(final byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private ResultActions protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort()));
  }

  private Repo helmRepo() {
    return this.seedRepo(RepoType.HELM, uniqueRepoName("helm"));
  }

  private Repo privateHelmRepo() {
    return this.seedRepo(RepoType.HELM, uniqueRepoName("helm-priv"), true, null);
  }

  /**
   * Asserts the OCI distribution error body ({@code {"errors":[{"code","message","detail"}]}}) of
   * an OCI endpoint (RPS-1039): exactly one error, and none of the panel envelope's keys.
   */
  private static void expectOciError(
      final MockHttpServletResponse response,
      final String code,
      final String message,
      final String detail)
      throws Exception {
    final var body = response.getContentAsString(StandardCharsets.UTF_8);

    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
    assertThat(JsonPath.<Map<String, Object>>read(body, "$")).containsOnlyKeys("errors");
    assertThat(JsonPath.<List<Object>>read(body, "$.errors")).hasSize(1);
    assertThat((String) JsonPath.read(body, "$.errors[0].code")).isEqualTo(code);
    assertThat((String) JsonPath.read(body, "$.errors[0].message")).isEqualTo(message);
    assertThat((String) JsonPath.read(body, "$.errors[0].detail")).isEqualTo(detail);
  }

  private static void requireStatus(
      final MockHttpServletResponse response, final int expected, final String step)
      throws Exception {
    if (response.getStatus() != expected) {
      throw new IllegalStateException(
          "%s answered %d instead of %d: %s"
              .formatted(
                  step,
                  response.getStatus(),
                  expected,
                  response.getContentAsString(StandardCharsets.UTF_8)));
    }
  }

  /** Archive whose {@code Chart.yaml} is exactly {@code chartYaml}. */
  private static byte[] archiveOf(final String chartName, final String chartYaml) {
    try {
      final var bytes = new ByteArrayOutputStream();
      try (final var gzip = new GZIPOutputStream(bytes);
          final var tar = new TarArchiveOutputStream(gzip)) {
        addEntry(tar, chartName + "/Chart.yaml", chartYaml);
      }
      return bytes.toByteArray();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Uploads a chart through {@code POST /{repo}/api/charts} (ChartMuseum-style). */
  private Pushed upload(final Repo repo, final ChartSpec spec, final String panelToken)
      throws Exception {
    final var token = this.asProtocolBearer(panelToken);
    final var bytes = archive(spec);
    final var response =
        this.protocol(
                multipart("/{repo}/api/charts", repo.getName())
                    .part(new MockPart("chart", spec.name() + "-" + spec.version() + ".tgz", bytes))
                    .header(AUTHORIZATION, token))
            .andReturn()
            .getResponse();
    requireStatus(response, 201, "chart upload");
    return new Pushed(spec, bytes);
  }

  /**
   * Pushes a chart through the OCI registry endpoints: start a blob upload, finalize it with the
   * chart archive as the layer, then put a manifest under {@code tag}.
   */
  private Pushed pushOci(
      final Repo repo,
      final String ociName,
      final String tag,
      final ChartSpec spec,
      final String panelToken)
      throws Exception {
    final var token = this.asProtocolBearer(panelToken);
    final var bytes = archive(spec);
    requireStatus(this.putOciChart(repo, ociName, tag, bytes, token), 201, "OCI manifest push");
    return new Pushed(spec, bytes);
  }

  /** Pushes {@code bytes} as the chart layer and answers the manifest {@code PUT}. */
  private MockHttpServletResponse putOciChart(
      final Repo repo,
      final String ociName,
      final String tag,
      final byte[] bytes,
      final String token)
      throws Exception {
    return this.putOciChart(repo, ociName, tag, bytes, null, List.of(), token);
  }

  /**
   * Pushes {@code bytes} as the chart layer and answers the manifest {@code PUT}. A non-null {@code
   * configBytes} is uploaded as the config blob the way {@code helm push} does (without it the
   * manifest names an empty config that is never uploaded), and every entry of {@code
   * provenanceLayers} is uploaded and listed after the chart layer.
   */
  private MockHttpServletResponse putOciChart(
      final Repo repo,
      final String ociName,
      final String tag,
      final byte[] bytes,
      final byte[] configBytes,
      final List<byte[]> provenanceLayers,
      final String token)
      throws Exception {
    final var layerDigest = sha256(bytes);
    final var config = configBytes == null ? "{}".getBytes(StandardCharsets.UTF_8) : configBytes;

    this.uploadOciBlob(repo, ociName, bytes, layerDigest, token);
    if (configBytes != null) {
      this.uploadOciBlob(repo, ociName, configBytes, sha256(configBytes), token);
    }
    final var layers = new StringBuilder();
    layers.append(layerJson(OCI_LAYER_TYPE, layerDigest, bytes.length));
    for (final var provenance : provenanceLayers) {
      this.uploadOciBlob(repo, ociName, provenance, sha256(provenance), token);
      layers
          .append(',')
          .append(layerJson(OCI_PROVENANCE_TYPE, sha256(provenance), provenance.length));
    }

    final var manifest =
        ("{\"schemaVersion\":2,\"mediaType\":\"%s\",\"config\":{\"mediaType\":\"%s\","
                + "\"digest\":\"%s\",\"size\":%d},\"layers\":[%s]}")
            .formatted(OCI_MANIFEST_TYPE, OCI_CONFIG_TYPE, sha256(config), config.length, layers);
    return this.protocol(
            put("/v2/{repo}/{name}/manifests/{tag}", repo.getName(), ociName, tag)
                .contentType(OCI_MANIFEST_TYPE)
                .content(manifest)
                .header(AUTHORIZATION, token))
        .andReturn()
        .getResponse();
  }

  private static String layerJson(final String mediaType, final String digest, final long size) {
    return "{\"mediaType\":\"%s\",\"digest\":\"%s\",\"size\":%d}"
        .formatted(mediaType, digest, size);
  }

  private void uploadOciBlob(
      final Repo repo,
      final String ociName,
      final byte[] bytes,
      final String digest,
      final String token)
      throws Exception {
    final var start =
        this.protocol(
                post("/v2/{repo}/{name}/blobs/uploads/", repo.getName(), ociName)
                    .header(AUTHORIZATION, token))
            .andReturn()
            .getResponse();
    requireStatus(start, 202, "OCI blob upload start");
    final var location = start.getHeader("Location");
    final var uploadId = location.substring(location.lastIndexOf('/') + 1);

    final var finalize =
        this.protocol(
                put("/v2/{repo}/{name}/blobs/uploads/{id}", repo.getName(), ociName, uploadId)
                    .param("digest", digest)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .content(bytes)
                    .header(AUTHORIZATION, token))
            .andReturn()
            .getResponse();
    requireStatus(finalize, 201, "OCI blob finalize");
  }

  /** Uploads a blob the way {@code helm push} streams it: start, one chunk, empty finalize. */
  private void uploadOciBlobChunked(
      final Repo repo,
      final String ociName,
      final byte[] bytes,
      final String digest,
      final String token)
      throws Exception {
    final var start =
        this.protocol(
                post("/v2/{repo}/{name}/blobs/uploads/", repo.getName(), ociName)
                    .header(AUTHORIZATION, token))
            .andReturn()
            .getResponse();
    requireStatus(start, 202, "OCI blob upload start");
    final var location = start.getHeader("Location");
    final var uploadId = location.substring(location.lastIndexOf('/') + 1);

    final var chunk =
        this.protocol(
                patch("/v2/{repo}/{name}/blobs/uploads/{id}", repo.getName(), ociName, uploadId)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .content(bytes)
                    .header(AUTHORIZATION, token))
            .andReturn()
            .getResponse();
    requireStatus(chunk, 202, "OCI blob chunk upload");

    final var finalize =
        this.protocol(
                put("/v2/{repo}/{name}/blobs/uploads/{id}", repo.getName(), ociName, uploadId)
                    .param("digest", digest)
                    .header(AUTHORIZATION, token))
            .andReturn()
            .getResponse();
    requireStatus(finalize, 201, "OCI blob finalize");
  }

  /**
   * Inserts a chart version literally named {@code tags} (the chart parser would refuse it, since
   * it is not semver) together with its package file, to exercise the {@code /{name}/tags} vs
   * {@code /{name}/{version}} route ambiguity.
   */
  private void seedVersionNamedTags(final Repo repo, final String chartName) throws IOException {
    final var content = "tags-package".getBytes(StandardCharsets.UTF_8);
    this.helmChartService.findOrCreate(
        HelmChartForm.builder()
            .name(chartName)
            .version("tags")
            .description("A version that is called tags")
            .appVersion("1.0.0")
            .type("application")
            .digest(sha256(content))
            .size(content.length)
            .build(),
        repo.getId());
    final var file = this.chartFile(repo, chartName, "tags");
    Files.createDirectories(file.getParent());
    Files.write(file, content);
    this.entityManager.flush();
    this.entityManager.clear();
  }

  /**
   * Stores a chart together with an OCI manifest under {@code ociName}, the state a push under a
   * name other than the {@code Chart.yaml} name left behind before RPS-978 rejected it.
   */
  private void seedOciManifestUnderOtherName(
      final Repo repo, final String ociName, final String reference, final ChartSpec spec) {
    final var content = "{}";
    final var bytes = archive(spec);
    final var chart =
        this.helmChartService.findOrCreate(
            HelmChartForm.builder()
                .name(spec.name())
                .version(spec.version())
                .description(spec.description())
                .appVersion(spec.appVersion())
                .type("application")
                .digest(sha256(bytes))
                .size(bytes.length)
                .build(),
            repo.getId());
    this.helmOciManifestService.save(
        HelmOciManifestForm.builder()
            .chartId(chart.id())
            .name(ociName)
            .reference(reference)
            .digest(sha256(content.getBytes(StandardCharsets.UTF_8)))
            .mediaType(OCI_MANIFEST_TYPE)
            .content(content)
            .build(),
        repo.getId());
    this.entityManager.flush();
    this.entityManager.clear();
  }

  private Path chartFile(final Repo repo, final String name, final String version) {
    return storageDirOf(repo).resolve("charts").resolve(name + "-" + version + ".tgz");
  }

  private Path ociManifestFile(final Repo repo, final String name, final String reference) {
    return storageDirOf(repo).resolve("oci").resolve("manifests").resolve(name).resolve(reference);
  }

  /** The digests of the blobs stored under the repo's {@code oci/blobs} directory. */
  private List<String> storedBlobs(final Repo repo) throws IOException {
    final var blobs = storageDirOf(repo).resolve("oci").resolve("blobs");
    if (!Files.isDirectory(blobs)) {
      return List.of();
    }
    try (final var files = Files.list(blobs)) {
      return files.map(file -> file.getFileName().toString()).sorted().toList();
    }
  }

  private long ociManifestFileSize(final Repo repo, final String name, final String reference)
      throws IOException {
    return Files.size(this.ociManifestFile(repo, name, reference));
  }

  private boolean blobRowExists(final Repo repo, final byte[] blob) {
    this.entityManager.flush();
    this.entityManager.clear();
    return this.helmOciBlobService.findByDigest(repo.getId(), sha256(blob)).isPresent();
  }

  /** The {@code index.yaml} an anonymous Helm client gets from a public repo. */
  private String indexYaml(final Repo repo) throws Exception {
    final var response =
        this.protocol(get("/{repo}/index.yaml", repo.getName())).andReturn().getResponse();
    requireStatus(response, 200, "index.yaml");
    return response.getContentAsString(StandardCharsets.UTF_8);
  }

  /** Versions of a chart as the database holds them right now, read past the first-level cache. */
  private List<String> storedVersions(final Repo repo, final String chartName) {
    this.entityManager.flush();
    this.entityManager.clear();
    return this.helmChartService.findAllVersionsByName(repo.getId(), chartName).stream()
        .map(info -> info.version())
        .toList();
  }

  private boolean chartRowExists(final Repo repo, final String chartName) {
    this.entityManager.flush();
    this.entityManager.clear();
    return this.helmChartRepository.findByRepoIdAndName(repo.getId(), chartName).isPresent();
  }

  /** The sum of every disk-usage delta requested for the repo since the last reset. */
  private long netUsage(final Repo repo) {
    final var captor = ArgumentCaptor.forClass(UsageChangedInfo.class);
    verify(this.usageUpdateService, atLeast(0)).updateUsage(captor.capture());

    return captor.getAllValues().stream()
        .filter(info -> info.repoId().equals(repo.getId()))
        .mapToLong(info -> info.usages().getDiskUsage())
        .sum();
  }

  private void verifyUsageDelta(final Repo repo, final long diskUsage) {
    verify(this.usageUpdateService)
        .updateUsage(new UsageChangedInfo(repo.getId(), BaseUsages.ofDisk(diskUsage)));
  }

  // ---------------------------------------------------------------------------------------------
  // Request and response helpers
  // ---------------------------------------------------------------------------------------------

  private static Map<String, Object> dataMap(final String body) {
    return JsonPath.read(body, "$.data");
  }

  private static List<Map<String, Object>> dataList(final String body) {
    return JsonPath.read(body, "$.data");
  }

  private static List<Map<String, Object>> content(final String body) {
    return JsonPath.read(body, "$.data.content");
  }

  private static List<String> stringList(final String body) {
    return JsonPath.read(body, "$.data");
  }

  private static List<String> namesOf(final String body) {
    return content(body).stream().map(item -> (String) item.get("name")).toList();
  }

  private static Map<String, Map<String, Object>> byVersion(final List<Map<String, Object>> items) {
    return items.stream()
        .collect(Collectors.toMap(item -> (String) item.get("version"), Function.identity()));
  }

  /** Asserts the exact key set of a DTO: every schema key except the ones that must be absent. */
  private static void assertKeys(
      final Map<String, Object> dto, final Set<String> schemaKeys, final String... absent) {
    final var expected = new HashSet<>(schemaKeys);
    expected.removeAll(Set.of(absent));
    assertThat(dto).containsOnlyKeys(expected);
  }

  private static void assertInstantBetween(
      final Object value, final Instant from, final Instant to) {
    assertThat(Instant.parse((String) value)).isBetween(from, to);
  }

  private static void assertPage(
      final String body, final int size, final int number, final int total, final int pages) {
    final Map<String, Object> page = JsonPath.read(body, "$.data.page");
    assertThat(page)
        .containsOnlyKeys("size", "number", "totalElements", "totalPages")
        .containsEntry("size", size)
        .containsEntry("number", number)
        .containsEntry("totalElements", total)
        .containsEntry("totalPages", pages);
  }

  private static void assertPagedModel(final String body) {
    final Map<String, Object> data = JsonPath.read(body, "$.data");
    assertThat(data).containsOnlyKeys("content", "page");
  }

  private String search(final Repo repo, final String token) throws Exception {
    return expectSuccess(
        this.perform(get("/api/helm/charts/{repo}", repo.getName()).header(AUTHORIZATION, token)),
        "chartsFetched");
  }

  private String searchWith(
      final Repo repo, final String token, final String param, final String value)
      throws Exception {
    return expectSuccess(
        this.perform(
            get("/api/helm/charts/{repo}", repo.getName())
                .param(param, value)
                .header(AUTHORIZATION, token)),
        "chartsFetched");
  }

  private ResultActions versionsRequest(final Repo repo, final String name, final String token)
      throws Exception {
    return this.perform(
        get("/api/helm/charts/{repo}/{name}", repo.getName(), name).header(AUTHORIZATION, token));
  }

  private String versions(final Repo repo, final String name, final String token) throws Exception {
    return expectSuccess(this.versionsRequest(repo, name, token), "chartVersionsFetched");
  }

  private ResultActions detailRequest(
      final Repo repo, final String name, final String version, final String token)
      throws Exception {
    return this.perform(
        get("/api/helm/charts/{repo}/{name}/{version}", repo.getName(), name, version)
            .header(AUTHORIZATION, token));
  }

  private String detail(
      final Repo repo, final String name, final String version, final String token)
      throws Exception {
    return expectSuccess(this.detailRequest(repo, name, version, token), "chartDetailFetched");
  }

  private ResultActions tagsRequest(final Repo repo, final String name, final String token)
      throws Exception {
    return this.perform(
        get("/api/helm/charts/{repo}/{name}/tags", repo.getName(), name)
            .header(AUTHORIZATION, token));
  }

  private String tags(final Repo repo, final String name, final String token) throws Exception {
    return expectSuccess(this.tagsRequest(repo, name, token), "chartTagsFetched");
  }

  private ResultActions deleteVersionRequest(
      final Repo repo, final String name, final String version, final String token)
      throws Exception {
    return this.perform(
        delete("/api/helm/charts/{repo}/{name}/{version}", repo.getName(), name, version)
            .header(AUTHORIZATION, token));
  }

  private ResultActions deleteAllRequest(final Repo repo, final String name, final String token)
      throws Exception {
    return this.perform(
        delete("/api/helm/charts/{repo}/{name}", repo.getName(), name)
            .header(AUTHORIZATION, token));
  }

  private static String expectSuccess(final ResultActions result, final String msgId)
      throws Exception {
    return expectSuccess(result, msgId, SUCCESS_TEXTS.get(msgId));
  }

  private static String expectDeleted(final ResultActions result) throws Exception {
    return expectSuccess(result, "chartDeleted");
  }

  private static void expectUnauthorized(final ResultActions result) throws Exception {
    expectError(
        result, HttpStatus.UNAUTHORIZED, "unAuthorized", "unAuthorized", NO_PERMISSION_TEXT);
  }

  private static void expectChartNotFound(final ResultActions result) throws Exception {
    expectError(result, HttpStatus.NOT_FOUND, "chartNotFound", "chartNotFound", "Chart not found.");
  }

  private static void expectRepoNotFound(final ResultActions result) throws Exception {
    expectError(
        result, HttpStatus.NOT_FOUND, "repoNotFound", "repoNotFound", "Repository not found");
  }

  private static void expectInternalError(final ResultActions result) throws Exception {
    expectError(
        result, HttpStatus.INTERNAL_SERVER_ERROR, "errorOccurred", null, "An error occurred.");
  }

  // ---------------------------------------------------------------------------------------------
  // GET /api/helm/charts/{repoName}
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("GET /api/helm/charts/{repoName} (search)")
  class Search {

    @Test
    @DisplayName("an empty repo yields an empty page with complete metadata")
    void emptyRepo() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var repo = it.helmRepo();

      final var body = it.search(repo, it.userBearerToken());

      assertPagedModel(body);
      assertThat(content(body)).isEmpty();
      assertPage(body, 10, 0, 0, 0);
    }

    @Test
    @DisplayName("lists one item per chart with the full HelmChartListItem shape, newest first")
    void listsLatestVersionOfEachChart() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      final var other = it.helmRepo();
      final var from = Instant.now().minusSeconds(5);
      it.upload(
          repo,
          ChartSpec.of("payments", "1.0.0").withRichMetadata().withAppVersion("2.0.0"),
          token);
      it.upload(repo, ChartSpec.of("payments", "1.1.0-rc.1"), token);
      it.upload(
          repo, ChartSpec.of("orders", "0.1.0").withDescription(null).withAppVersion(null), token);
      it.upload(repo, ChartSpec.of("inventory", "3.0.0"), token);
      it.upload(other, ChartSpec.of("elsewhere", "1.0.0"), token);
      final var to = Instant.now().plusSeconds(5);

      final var body = it.search(repo, token);

      assertPagedModel(body);
      assertPage(body, 10, 0, 3, 1);
      assertThat(namesOf(body)).containsExactly("inventory", "orders", "payments");

      final var items = content(body);
      final var inventory = items.get(0);
      assertKeys(inventory, LIST_ITEM_KEYS);
      assertThat(inventory)
          .containsEntry("name", "inventory")
          .containsEntry("latestVersion", "3.0.0")
          .containsEntry("description", "inventory chart")
          .containsEntry("type", "application");
      assertInstantBetween(inventory.get("updatedAt"), from, to);

      // A chart without description has no such property: the API omits nulls.
      final var orders = items.get(1);
      assertKeys(orders, LIST_ITEM_KEYS, "description");
      assertThat(orders)
          .containsEntry("name", "orders")
          .containsEntry("latestVersion", "0.1.0")
          .containsEntry("type", "application");

      // Only the most recently created version represents a chart, and the item does not leak
      // the Chart.yaml extras (keywords, maintainers, dependencies, annotations).
      final var payments = items.get(2);
      assertKeys(payments, LIST_ITEM_KEYS);
      assertThat(payments)
          .containsEntry("name", "payments")
          .containsEntry("latestVersion", "1.1.0-rc.1")
          .containsEntry("description", "payments chart");
    }

    @Test
    @DisplayName("'latest' is the most recently uploaded version, not the highest semver")
    void latestIsMostRecentlyUploaded() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      it.upload(repo, ChartSpec.of("payments", "2.0.0"), token);
      it.upload(repo, ChartSpec.of("payments", "1.5.0"), token);

      final var body = it.search(repo, token);

      assertThat(content(body)).hasSize(1);
      assertThat(content(body).getFirst()).containsEntry("latestVersion", "1.5.0");
    }

    @Test
    @DisplayName("lists a chart once when two of its versions were created in the same instant")
    void tiedCreatedAtListsChartOnce() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      it.upload(repo, ChartSpec.of("payments", "1.0.0"), token);
      it.upload(repo, ChartSpec.of("payments", "1.1.0"), token);
      it.upload(repo, ChartSpec.of("orders", "0.1.0"), token);
      it.upload(repo, ChartSpec.of("orders", "0.2.0"), token);
      it.upload(repo, ChartSpec.of("inventory", "3.0.0"), token);
      it.entityManager.flush();
      // Both versions of "payments" and of "orders" now share one createdAt.
      it.jdbcTemplate.update(
          "update helm_chart_version set created_at = ? where chart_id in"
              + " (select id from helm_chart where repo_id = ?)",
          Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
          repo.getId());
      it.entityManager.clear();

      final var body = it.search(repo, token);

      assertPage(body, 10, 0, 3, 1);
      assertThat(namesOf(body)).containsExactlyInAnyOrder("inventory", "orders", "payments");
      // The tie goes to the version the version list puts first: the greater id.
      for (final var name : List.of("orders", "payments")) {
        final var chart =
            it.helmChartRepository.findByRepoIdAndName(repo.getId(), name).orElseThrow();
        final var newest =
            it.helmChartVersionRepository
                .findAllByChartOrderByCreatedAtDescIdDesc(chart)
                .getFirst();
        assertThat(content(body))
            .filteredOn(item -> name.equals(item.get("name")))
            .singleElement()
            .satisfies(
                item -> assertThat(item).containsEntry("latestVersion", newest.getVersion()));
      }
    }

    @Test
    @DisplayName("the query matches chart names only, as a case-insensitive substring")
    void queryMatchesNamePartially() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      it.upload(repo, ChartSpec.of("payments", "1.0.0"), token);
      it.upload(repo, ChartSpec.of("payment-gateway", "1.0.0"), token);
      it.upload(repo, ChartSpec.of("orders", "1.0.0"), token);

      assertThat(namesOf(it.searchWith(repo, token, "query", "payment")))
          .containsExactlyInAnyOrder("payments", "payment-gateway");
      assertThat(namesOf(it.searchWith(repo, token, "query", "PAYMENTS")))
          .containsExactly("payments");
      assertThat(namesOf(it.searchWith(repo, token, "query", "ent")))
          .containsExactlyInAnyOrder("payments", "payment-gateway");
      assertThat(namesOf(it.searchWith(repo, token, "query", "gateway")))
          .containsExactly("payment-gateway");
      assertThat(namesOf(it.searchWith(repo, token, "query", ""))).hasSize(3);

      // "chart" is in every description ("<name> chart") but in no name.
      final var noMatch = it.searchWith(repo, token, "query", "chart");
      assertThat(content(noMatch)).isEmpty();
      assertPage(noMatch, 10, 0, 0, 0);
    }

    @Test
    @DisplayName("pages with size/page and reports complete PagedModel metadata")
    void pagesResults() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      for (final var name : List.of("alpha", "bravo", "charlie", "delta", "echo")) {
        it.upload(repo, ChartSpec.of(name, "1.0.0"), token);
      }

      // Default order is lastUpdatedAt descending: the last upload comes first.
      final var first = this.page(repo, token, 0);
      assertThat(namesOf(first)).containsExactly("echo", "delta");
      assertPage(first, 2, 0, 5, 3);
      final var second = this.page(repo, token, 1);
      assertThat(namesOf(second)).containsExactly("charlie", "bravo");
      assertPage(second, 2, 1, 5, 3);
      final var third = this.page(repo, token, 2);
      assertThat(namesOf(third)).containsExactly("alpha");
      assertPage(third, 2, 2, 5, 3);

      // Past the last page: no content, same totals.
      final var beyond = this.page(repo, token, 99);
      assertThat(content(beyond)).isEmpty();
      assertPage(beyond, 2, 99, 5, 3);
    }

    private String page(final Repo repo, final String token, final int number) throws Exception {
      return expectSuccess(
          HelmChartControllerIT.this.perform(
              get("/api/helm/charts/{repo}", repo.getName())
                  .param("size", "2")
                  .param("page", String.valueOf(number))
                  .header(AUTHORIZATION, token)),
          "chartsFetched");
    }

    @Test
    @DisplayName("honours explicit sort keys")
    void sortsByExplicitKeys() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      for (final var name : List.of("charlie", "alpha", "bravo")) {
        it.upload(repo, ChartSpec.of(name, "1.0.0"), token);
      }

      assertThat(namesOf(it.searchWith(repo, token, "sort", "lastUpdatedAt,desc")))
          .containsExactly("bravo", "alpha", "charlie");
      assertThat(namesOf(it.searchWith(repo, token, "sort", "lastUpdatedAt,asc")))
          .containsExactly("charlie", "alpha", "bravo");
      assertThat(namesOf(it.searchWith(repo, token, "sort", "name,asc")))
          .containsExactly("alpha", "bravo", "charlie");
      assertThat(namesOf(it.searchWith(repo, token, "sort", "name,desc")))
          .containsExactly("charlie", "bravo", "alpha");
    }

    @Test
    @DisplayName("sorts by the list item's own keys: updatedAt and latestVersion")
    void sortsByListItemKeys() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      it.upload(repo, ChartSpec.of("charlie", "1.0.0"), token);
      it.upload(repo, ChartSpec.of("alpha", "3.0.0"), token);
      it.upload(repo, ChartSpec.of("bravo", "2.0.0"), token);

      assertThat(namesOf(it.searchWith(repo, token, "sort", "updatedAt,desc")))
          .containsExactly("bravo", "alpha", "charlie");
      assertThat(namesOf(it.searchWith(repo, token, "sort", "updatedAt,asc")))
          .containsExactly("charlie", "alpha", "bravo");
      assertThat(namesOf(it.searchWith(repo, token, "sort", "latestVersion,asc")))
          .containsExactly("charlie", "bravo", "alpha");
      assertThat(namesOf(it.searchWith(repo, token, "sort", "latestVersion,desc")))
          .containsExactly("alpha", "bravo", "charlie");
    }

    @Test
    @DisplayName("keeps the createdAt key the panel sends")
    void sortsByCreatedAt() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      for (final var name : List.of("charlie", "alpha", "bravo")) {
        it.upload(repo, ChartSpec.of(name, "1.0.0"), token);
      }

      assertThat(namesOf(it.searchWith(repo, token, "sort", "createdAt,desc")))
          .containsExactly("bravo", "alpha", "charlie");
      assertThat(namesOf(it.searchWith(repo, token, "sort", "createdAt,asc")))
          .containsExactly("charlie", "alpha", "bravo");
    }

    /**
     * The entity paths the query sorts by ({@code chart.name}, {@code version}) are not part of the
     * API, so they are rejected like any other unknown key.
     */
    @ParameterizedTest(name = "sort={0}")
    @ValueSource(strings = {"bogus,asc", "chart.name,asc", "version,asc", "Name,asc"})
    @DisplayName("returns 400 validationError naming sort for an unsupported sort property")
    void unknownSortPropertyIs400(final String sort) throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      it.upload(repo, ChartSpec.of("payments", "1.0.0"), token);

      expectError(
          it.perform(
              get("/api/helm/charts/{repo}", repo.getName())
                  .param("sort", sort)
                  .header(AUTHORIZATION, token)),
          HttpStatus.BAD_REQUEST,
          "validationError",
          "sort",
          "Incoming data couldn't be validated.");
    }

    @Test
    @DisplayName("rejects the request when only one of several sort properties is unsupported")
    void oneUnknownAmongSeveralSortProperties() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      it.upload(repo, ChartSpec.of("payments", "1.0.0"), token);

      expectError(
          it.perform(
              get("/api/helm/charts/{repo}", repo.getName())
                  .param("sort", "name,asc")
                  .param("sort", "bogus,desc")
                  .header(AUTHORIZATION, token)),
          HttpStatus.BAD_REQUEST,
          "validationError",
          "sort",
          "Incoming data couldn't be validated.");
    }

    @Test
    @DisplayName("applies several supported sort keys in order")
    void sortsByMultipleKeys() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      it.upload(repo, ChartSpec.of("charlie", "1.0.0"), token);
      it.upload(repo, ChartSpec.of("alpha", "1.0.0"), token);
      it.upload(repo, ChartSpec.of("bravo", "2.0.0"), token);

      final var body =
          expectSuccess(
              it.perform(
                  get("/api/helm/charts/{repo}", repo.getName())
                      .param("sort", "latestVersion,desc")
                      .param("sort", "name,asc")
                      .header(AUTHORIZATION, token)),
              "chartsFetched");

      assertThat(namesOf(body)).containsExactly("bravo", "alpha", "charlie");
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("invalidPagingParams")
    @DisplayName("returns 400 validationError naming the parameter for a bad page or size")
    void invalidPagingParam(final String param, final String value) throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      it.upload(repo, ChartSpec.of("only", "1.0.0"), token);

      expectError(
          it.perform(
              get("/api/helm/charts/{repo}", repo.getName())
                  .param(param, value)
                  .header(AUTHORIZATION, token)),
          HttpStatus.BAD_REQUEST,
          "validationError",
          param,
          "Incoming data couldn't be validated.");
    }

    static Stream<Arguments> invalidPagingParams() {
      return Stream.of(
          Arguments.of("page", "abc"),
          Arguments.of("size", "abc"),
          Arguments.of("page", "-1"),
          Arguments.of("size", "0"),
          Arguments.of("size", "-1"),
          Arguments.of("size", "101"));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // GET /api/helm/charts/{repoName}/{name}
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("GET /api/helm/charts/{repoName}/{name} (versions)")
  class Versions {

    @Test
    @DisplayName("lists every version with the full HelmChartVersionItem shape")
    void listsAllVersions() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      final var from = Instant.now().minusSeconds(5);
      final var stable =
          it.upload(
              repo,
              ChartSpec.of("payments", "1.0.0").withRichMetadata().withAppVersion("2.0.0"),
              token);
      final var prerelease = it.upload(repo, ChartSpec.of("payments", "1.1.0-rc.1"), token);
      final var build =
          it.upload(repo, ChartSpec.of("payments", "1.2.0+build.5").withDescription(null), token);
      it.upload(repo, ChartSpec.of("orders", "0.1.0"), token);
      final var to = Instant.now().plusSeconds(5);

      final var body = it.versions(repo, "payments", token);

      // The list is a plain, unpaged array, newest upload first.
      final var items = dataList(body);
      assertThat(items)
          .extracting(item -> item.get("version"))
          .containsExactly("1.2.0+build.5", "1.1.0-rc.1", "1.0.0");
      items.forEach(item -> assertInstantBetween(item.get("createdAt"), from, to));

      final var versions = byVersion(items);

      final var stableItem = versions.get("1.0.0");
      assertKeys(stableItem, VERSION_ITEM_KEYS);
      assertThat(stableItem)
          .containsEntry("version", "1.0.0")
          .containsEntry("appVersion", "2.0.0")
          .containsEntry("description", "payments chart")
          .containsEntry("type", "application")
          .containsEntry("digest", stable.digest())
          .containsEntry("size", (int) stable.size());

      final var prereleaseItem = versions.get("1.1.0-rc.1");
      assertKeys(prereleaseItem, VERSION_ITEM_KEYS);
      assertThat(prereleaseItem)
          .containsEntry("appVersion", "1.0.0")
          .containsEntry("digest", prerelease.digest())
          .containsEntry("size", (int) prerelease.size());

      final var buildItem = versions.get("1.2.0+build.5");
      assertKeys(buildItem, VERSION_ITEM_KEYS, "description");
      assertThat(buildItem)
          .containsEntry("digest", build.digest())
          .containsEntry("size", (int) build.size());
    }

    @Test
    @DisplayName("only lists versions of the requested chart in the requested repo")
    void isScopedToRepoAndChart() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      final var other = it.helmRepo();
      it.upload(repo, ChartSpec.of("payments", "1.0.0"), token);
      it.upload(repo, ChartSpec.of("orders", "5.0.0"), token);
      it.upload(other, ChartSpec.of("payments", "9.0.0"), token);

      assertThat(byVersion(dataList(it.versions(repo, "payments", token))))
          .containsOnlyKeys("1.0.0");
      assertThat(byVersion(dataList(it.versions(other, "payments", token))))
          .containsOnlyKeys("9.0.0");
    }

    @Test
    @DisplayName("lists versions of a chart that was pushed through OCI")
    void listsOciPushedChart() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      final var pushed =
          it.pushOci(repo, "ocichart", "0.5.0", ChartSpec.of("ocichart", "0.5.0"), token);

      final var items = dataList(it.versions(repo, "ocichart", token));

      assertThat(items).hasSize(1);
      assertKeys(items.getFirst(), VERSION_ITEM_KEYS);
      assertThat(items.getFirst())
          .containsEntry("version", "0.5.0")
          .containsEntry("digest", pushed.digest())
          .containsEntry("size", (int) pushed.size());
    }

    /**
     * The order is by upload time (newest first), the same notion of "latest" the chart list uses;
     * it does not follow the semantic version, so an older release uploaded last comes first.
     */
    @Test
    @DisplayName("lists the most recently uploaded version first, whatever its version number")
    void newestUploadFirst() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      it.upload(repo, ChartSpec.of("payments", "2.0.0"), token);
      it.upload(repo, ChartSpec.of("payments", "1.0.0"), token);
      it.upload(repo, ChartSpec.of("payments", "1.5.0"), token);

      final var items = dataList(it.versions(repo, "payments", token));

      assertThat(items)
          .extracting(item -> item.get("version"))
          .containsExactly("1.5.0", "1.0.0", "2.0.0");
    }

    @Test
    @DisplayName("an unknown chart is 404 chartNotFound, like detail and delete")
    void unknownChartIsNotFound() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      final var other = it.helmRepo();
      it.upload(repo, ChartSpec.of("payments", "1.0.0"), token);
      it.upload(other, ChartSpec.of("elsewhere", "1.0.0"), token);

      expectChartNotFound(it.versionsRequest(repo, "does-not-exist", it.userBearerToken()));
      // A chart that exists only in another repo is not visible here.
      expectChartNotFound(it.versionsRequest(repo, "elsewhere", token));
      // The chart name is matched exactly: no prefix, no case folding.
      expectChartNotFound(it.versionsRequest(repo, "pay", token));
      expectChartNotFound(it.versionsRequest(repo, "PAYMENTS", token));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // GET /api/helm/charts/{repoName}/{name}/{version}
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("GET /api/helm/charts/{repoName}/{name}/{version} (detail)")
  class Detail {

    @Test
    @DisplayName("returns the full HelmChartDetail and nothing from the Chart.yaml extras")
    void returnsFullDetail() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      final var from = Instant.now().minusSeconds(5);
      final var pushed =
          it.upload(
              repo,
              ChartSpec.of("payments", "1.0.0").withRichMetadata().withAppVersion("2.0.0"),
              token);
      it.upload(repo, ChartSpec.of("payments", "1.1.0-rc.1"), token);
      final var to = Instant.now().plusSeconds(5);

      final var detail = dataMap(it.detail(repo, "payments", "1.0.0", token));

      // Keywords, maintainers, dependencies, annotations, README and values.yaml are in the
      // archive but not part of the DTO: the key set is exactly the schema.
      assertKeys(detail, DETAIL_KEYS);
      assertThat(detail)
          .containsEntry("name", "payments")
          .containsEntry("version", "1.0.0")
          .containsEntry("description", "payments chart")
          .containsEntry("appVersion", "2.0.0")
          .containsEntry("type", "application")
          .containsEntry("digest", pushed.digest())
          .containsEntry("size", (int) pushed.size());
      assertInstantBetween(detail.get("createdAt"), from, to);
      assertInstantBetween(detail.get("lastUpdatedAt"), from, to);
    }

    @Test
    @DisplayName("omits description and appVersion when Chart.yaml has none")
    void omitsMissingOptionalFields() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      it.upload(
          repo, ChartSpec.of("orders", "0.1.0").withDescription(null).withAppVersion(null), token);

      final var detail = dataMap(it.detail(repo, "orders", "0.1.0", token));

      assertKeys(detail, DETAIL_KEYS, "description", "appVersion");
    }

    @Test
    @DisplayName("resolves prerelease and build-metadata versions")
    void resolvesPrereleaseAndBuildMetadata() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      final var prerelease = it.upload(repo, ChartSpec.of("payments", "1.1.0-rc.1"), token);
      final var build = it.upload(repo, ChartSpec.of("payments", "1.2.0+build.5"), token);

      assertThat(dataMap(it.detail(repo, "payments", "1.1.0-rc.1", token)))
          .containsEntry("version", "1.1.0-rc.1")
          .containsEntry("digest", prerelease.digest());
      assertThat(dataMap(it.detail(repo, "payments", "1.2.0+build.5", token)))
          .containsEntry("version", "1.2.0+build.5")
          .containsEntry("digest", build.digest());
    }

    @Test
    @DisplayName("returns an OCI-pushed chart with the layer digest and size")
    void returnsOciPushedChart() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      final var pushed =
          it.pushOci(repo, "ocichart", "latest", ChartSpec.of("ocichart", "0.5.0"), token);

      final var detail = dataMap(it.detail(repo, "ocichart", "0.5.0", token));

      assertKeys(detail, DETAIL_KEYS);
      assertThat(detail)
          .containsEntry("name", "ocichart")
          .containsEntry("version", "0.5.0")
          .containsEntry("digest", pushed.digest())
          .containsEntry("size", (int) pushed.size());
    }

    @Test
    @DisplayName("an unknown version or chart is 404 chartNotFound")
    void unknownVersionOrChart() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      final var other = it.helmRepo();
      it.upload(repo, ChartSpec.of("payments", "1.0.0"), token);
      it.upload(other, ChartSpec.of("elsewhere", "1.0.0"), token);

      expectChartNotFound(it.detailRequest(repo, "payments", "9.9.9", token));
      expectChartNotFound(it.detailRequest(repo, "does-not-exist", "1.0.0", token));
      // A chart that exists only in another repo is not visible here.
      expectChartNotFound(it.detailRequest(repo, "elsewhere", "1.0.0", token));
      // Versions are matched exactly: no prefix, no case folding.
      expectChartNotFound(it.detailRequest(repo, "payments", "1.0", token));
      expectChartNotFound(it.detailRequest(repo, "PAYMENTS", "1.0.0", token));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // GET /api/helm/charts/{repoName}/{name}/tags
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("GET /api/helm/charts/{repoName}/{name}/tags (OCI tags)")
  class Tags {

    @Test
    @DisplayName("lists every OCI tag of the chart")
    void listsOciTags() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      it.pushOci(repo, "payments", "1.0.0", ChartSpec.of("payments", "1.0.0"), token);
      it.pushOci(repo, "payments", "latest", ChartSpec.of("payments", "1.0.0"), token);
      it.pushOci(repo, "payments", "1.1.0", ChartSpec.of("payments", "1.1.0"), token);

      final var body = it.tags(repo, "payments", token);

      assertThat(stringList(body)).containsExactlyInAnyOrder("1.0.0", "latest", "1.1.0");
    }

    @Test
    @DisplayName("is scoped to the chart name and the repo")
    void isScopedToChartAndRepo() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      final var other = it.helmRepo();
      it.pushOci(repo, "payments", "1.0.0", ChartSpec.of("payments", "1.0.0"), token);
      it.pushOci(repo, "orders", "0.1.0", ChartSpec.of("orders", "0.1.0"), token);
      it.pushOci(other, "payments", "9.0.0", ChartSpec.of("payments", "9.0.0"), token);

      assertThat(stringList(it.tags(repo, "payments", token))).containsExactly("1.0.0");
      assertThat(stringList(it.tags(repo, "orders", token))).containsExactly("0.1.0");
      assertThat(stringList(it.tags(other, "payments", token))).containsExactly("9.0.0");
    }

    /** A chart that exists but was only uploaded the classic way has no tags: that is not a 404. */
    @Test
    @DisplayName("a chart without OCI tags has an empty tag list")
    void noTagsIsEmptyList() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      it.upload(repo, ChartSpec.of("classic", "1.0.0"), token);
      it.pushOci(repo, "oci", "1.0.0", ChartSpec.of("oci", "1.0.0"), token);

      assertThat(stringList(it.tags(repo, "classic", token))).isEmpty();
      assertThat(stringList(it.tags(repo, "oci", token))).containsExactly("1.0.0");
    }

    @Test
    @DisplayName("an unknown chart is 404 chartNotFound, unlike a chart without tags")
    void unknownChartIsNotFound() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      final var other = it.helmRepo();
      it.pushOci(repo, "payments", "1.0.0", ChartSpec.of("payments", "1.0.0"), token);
      it.pushOci(other, "elsewhere", "1.0.0", ChartSpec.of("elsewhere", "1.0.0"), token);

      expectChartNotFound(it.tagsRequest(repo, "does-not-exist", it.userBearerToken()));
      // A chart that exists only in another repo is not visible here.
      expectChartNotFound(it.tagsRequest(repo, "elsewhere", token));
      expectChartNotFound(it.tagsRequest(repo, "PAYMENTS", token));
    }

    /**
     * A push under a name other than the {@code Chart.yaml} name is rejected (RPS-978). Manifests
     * stored before that check can carry such a name; the repair (RPS-1038) re-keys them to the
     * chart name, after which the tags are listed under the chart and the path name is unknown.
     */
    @Test
    @DisplayName("tags stored under a name that differs from the chart name move to the chart")
    void tagsOfNameDifferingFromChartNameMoveToTheChart() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      it.seedOciManifestUnderOtherName(repo, "alias", "1.0.0", ChartSpec.of("real-name", "1.0.0"));

      expectChartNotFound(it.tagsRequest(repo, "alias", token));
      assertThat(stringList(it.tags(repo, "real-name", token))).isEmpty();

      it.helmOciManifestNameRepairService.repair();
      it.entityManager.clear();

      assertThat(stringList(it.tags(repo, "real-name", token))).containsExactly("1.0.0");
      expectChartNotFound(it.tagsRequest(repo, "alias", token));
    }

    /**
     * {@code /{name}/tags} and {@code /{name}/{version}} overlap. Spring prefers the literal
     * segment, so {@code GET .../tags} is always the OCI tag list: a chart version that is really
     * called {@code tags} is listed among the versions but its detail cannot be fetched. For {@code
     * DELETE} there is no literal {@code tags} route, so it deletes that version.
     */
    @Test
    @DisplayName("a version literally named 'tags': GET is the tag list, DELETE is the version")
    void routeAmbiguityWithVersionNamedTags() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      it.pushOci(repo, "routing", "1.0.0", ChartSpec.of("routing", "1.0.0"), token);
      it.seedVersionNamedTags(repo, "routing");

      assertThat(byVersion(dataList(it.versions(repo, "routing", token))))
          .containsOnlyKeys("1.0.0", "tags");

      // GET .../tags is the OCI tag list (["1.0.0"]), not the detail of version "tags".
      assertThat(stringList(it.tags(repo, "routing", token))).containsExactly("1.0.0");

      expectDeleted(
          it.perform(
              delete("/api/helm/charts/{repo}/{name}/tags", repo.getName(), "routing")
                  .header(AUTHORIZATION, token)));
      assertThat(it.storedVersions(repo, "routing")).containsExactly("1.0.0");
      assertThat(Files.exists(it.chartFile(repo, "routing", "tags"))).isFalse();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // DELETE /api/helm/charts/{repoName}/{name}/{version}
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("DELETE /api/helm/charts/{repoName}/{name}/{version}")
  class DeleteVersion {

    @Test
    @DisplayName("removes only that version: row, package file, index entry and usage")
    void removesOnlyThatVersion() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      it.upload(repo, ChartSpec.of("payments", "1.0.0"), token);
      final var doomed = it.upload(repo, ChartSpec.of("payments", "1.1.0-rc.1"), token);
      it.upload(repo, ChartSpec.of("orders", "0.1.0"), token);
      assertThat(it.indexYaml(repo)).contains("charts/payments-1.1.0-rc.1.tgz");
      clearInvocations(it.usageUpdateService);

      final var body =
          expectDeleted(it.deleteVersionRequest(repo, "payments", "1.1.0-rc.1", token));

      assertThat(JsonPath.<Object>read(body, "$.data")).isNull();
      assertThat(it.storedVersions(repo, "payments")).containsExactly("1.0.0");
      assertThat(Files.exists(it.chartFile(repo, "payments", "1.1.0-rc.1"))).isFalse();
      assertThat(Files.exists(it.chartFile(repo, "payments", "1.0.0"))).isTrue();
      assertThat(Files.exists(it.chartFile(repo, "orders", "0.1.0"))).isTrue();
      assertThat(it.indexYaml(repo))
          .doesNotContain("payments-1.1.0-rc.1.tgz")
          .contains("charts/payments-1.0.0.tgz")
          .contains("charts/orders-0.1.0.tgz");
      it.verifyUsageDelta(repo, -doomed.size());

      // The sibling version is untouched and still served through the API.
      assertThat(byVersion(dataList(it.versions(repo, "payments", token))))
          .containsOnlyKeys("1.0.0");
      assertThat(namesOf(it.search(repo, token))).containsExactlyInAnyOrder("payments", "orders");
    }

    @Test
    @DisplayName("deleting the last version removes the chart itself")
    void deletingLastVersionRemovesChart() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      it.upload(repo, ChartSpec.of("payments", "1.0.0"), token);
      it.upload(repo, ChartSpec.of("orders", "0.1.0"), token);
      assertThat(it.chartRowExists(repo, "payments")).isTrue();

      expectDeleted(it.deleteVersionRequest(repo, "payments", "1.0.0", token));

      assertThat(it.chartRowExists(repo, "payments")).isFalse();
      assertThat(it.storedVersions(repo, "payments")).isEmpty();
      assertThat(it.chartRowExists(repo, "orders")).isTrue();
      assertThat(namesOf(it.search(repo, token))).containsExactly("orders");
      expectChartNotFound(it.versionsRequest(repo, "payments", token));
      assertThat(it.indexYaml(repo)).doesNotContain("payments-1.0.0.tgz");
    }

    @Test
    @DisplayName("removes the OCI manifests and tags of that version only")
    void removesOciManifestsOfThatVersion() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      it.pushOci(repo, "payments", "1.0.0", ChartSpec.of("payments", "1.0.0"), token);
      it.pushOci(repo, "payments", "stable", ChartSpec.of("payments", "1.0.0"), token);
      it.pushOci(repo, "payments", "1.1.0", ChartSpec.of("payments", "1.1.0"), token);
      assertThat(Files.exists(it.ociManifestFile(repo, "payments", "1.0.0"))).isTrue();

      expectDeleted(it.deleteVersionRequest(repo, "payments", "1.0.0", token));

      assertThat(stringList(it.tags(repo, "payments", token))).containsExactly("1.1.0");
      assertThat(Files.exists(it.ociManifestFile(repo, "payments", "1.0.0"))).isFalse();
      assertThat(Files.exists(it.ociManifestFile(repo, "payments", "stable"))).isFalse();
      assertThat(Files.exists(it.ociManifestFile(repo, "payments", "1.1.0"))).isTrue();
      assertThat(it.storedVersions(repo, "payments")).containsExactly("1.1.0");
    }

    @Test
    @DisplayName("a second delete, an unknown version and an unknown chart are 404 chartNotFound")
    void notFound() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      it.upload(repo, ChartSpec.of("payments", "1.0.0"), token);
      it.upload(repo, ChartSpec.of("payments", "1.1.0"), token);

      expectDeleted(it.deleteVersionRequest(repo, "payments", "1.0.0", token));
      expectChartNotFound(it.deleteVersionRequest(repo, "payments", "1.0.0", token));
      expectChartNotFound(it.deleteVersionRequest(repo, "payments", "9.9.9", token));
      expectChartNotFound(it.deleteVersionRequest(repo, "does-not-exist", "1.0.0", token));

      // None of the failed deletes touched the surviving version.
      assertThat(it.storedVersions(repo, "payments")).containsExactly("1.1.0");
      assertThat(Files.exists(it.chartFile(repo, "payments", "1.1.0"))).isTrue();
    }

    @Test
    @DisplayName(
        "a caller without MANAGE changes nothing: a plain user gets 403, an anonymous caller 401")
    void requiresManage() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var repo = it.helmRepo();
      it.upload(repo, ChartSpec.of("payments", "1.0.0"), it.adminBearerToken());

      expectForbidden(it.deleteVersionRequest(repo, "payments", "1.0.0", it.userBearerToken()));
      expectUnauthorized(
          it.perform(
              delete(
                  "/api/helm/charts/{repo}/{name}/{version}",
                  repo.getName(),
                  "payments",
                  "1.0.0")));

      assertThat(it.storedVersions(repo, "payments")).containsExactly("1.0.0");
      assertThat(Files.exists(it.chartFile(repo, "payments", "1.0.0"))).isTrue();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // DELETE /api/helm/charts/{repoName}/{name}
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("DELETE /api/helm/charts/{repoName}/{name}")
  class DeleteAll {

    @Test
    @DisplayName("removes every version: rows, package files, index entries and usage")
    void removesEveryVersion() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      final var one = it.upload(repo, ChartSpec.of("payments", "1.0.0"), token);
      final var two = it.upload(repo, ChartSpec.of("payments", "1.1.0-rc.1"), token);
      final var three = it.upload(repo, ChartSpec.of("payments", "1.2.0+build.5"), token);
      it.upload(repo, ChartSpec.of("orders", "0.1.0"), token);
      clearInvocations(it.usageUpdateService);

      final var body = expectDeleted(it.deleteAllRequest(repo, "payments", token));

      assertThat(JsonPath.<Object>read(body, "$.data")).isNull();
      assertThat(it.chartRowExists(repo, "payments")).isFalse();
      assertThat(it.storedVersions(repo, "payments")).isEmpty();
      for (final var version : List.of("1.0.0", "1.1.0-rc.1", "1.2.0+build.5")) {
        assertThat(Files.exists(it.chartFile(repo, "payments", version))).as(version).isFalse();
      }
      assertThat(it.indexYaml(repo))
          .doesNotContain("payments-")
          .contains("charts/orders-0.1.0.tgz");
      it.verifyUsageDelta(repo, -(one.size() + two.size() + three.size()));

      // Other charts are untouched.
      assertThat(it.chartRowExists(repo, "orders")).isTrue();
      assertThat(Files.exists(it.chartFile(repo, "orders", "0.1.0"))).isTrue();
      assertThat(namesOf(it.search(repo, token))).containsExactly("orders");
      expectChartNotFound(it.versionsRequest(repo, "payments", token));
    }

    @Test
    @DisplayName("also removes the OCI manifests and tags")
    void removesOciManifests() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      it.pushOci(repo, "payments", "1.0.0", ChartSpec.of("payments", "1.0.0"), token);
      it.pushOci(repo, "payments", "latest", ChartSpec.of("payments", "1.0.0"), token);
      it.pushOci(repo, "orders", "0.1.0", ChartSpec.of("orders", "0.1.0"), token);

      expectDeleted(it.deleteAllRequest(repo, "payments", token));

      expectChartNotFound(it.tagsRequest(repo, "payments", token));
      assertThat(Files.exists(it.ociManifestFile(repo, "payments", "1.0.0"))).isFalse();
      assertThat(Files.exists(it.ociManifestFile(repo, "payments", "latest"))).isFalse();
      assertThat(stringList(it.tags(repo, "orders", token))).containsExactly("0.1.0");
      assertThat(Files.exists(it.ociManifestFile(repo, "orders", "0.1.0"))).isTrue();
    }

    @Test
    @DisplayName("a second delete and an unknown chart are 404 chartNotFound")
    void notFound() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      it.upload(repo, ChartSpec.of("payments", "1.0.0"), token);

      expectDeleted(it.deleteAllRequest(repo, "payments", token));
      expectChartNotFound(it.deleteAllRequest(repo, "payments", token));
      expectChartNotFound(it.deleteAllRequest(repo, "does-not-exist", token));
    }

    @Test
    @DisplayName(
        "a caller without MANAGE changes nothing: a plain user gets 403, an anonymous caller 401")
    void requiresManage() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var repo = it.helmRepo();
      final var admin = it.adminBearerToken();
      it.upload(repo, ChartSpec.of("payments", "1.0.0"), admin);
      it.upload(repo, ChartSpec.of("payments", "1.1.0"), admin);

      expectForbidden(it.deleteAllRequest(repo, "payments", it.userBearerToken()));
      expectUnauthorized(
          it.perform(delete("/api/helm/charts/{repo}/{name}", repo.getName(), "payments")));

      assertThat(it.storedVersions(repo, "payments")).containsExactlyInAnyOrder("1.0.0", "1.1.0");
      assertThat(Files.exists(it.chartFile(repo, "payments", "1.0.0"))).isTrue();
      assertThat(Files.exists(it.chartFile(repo, "payments", "1.1.0"))).isTrue();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Authentication and repository resolution, across all six endpoints
  // ---------------------------------------------------------------------------------------------

  /** The six endpoints, each addressed at the chart {@code payments} version {@code 1.0.0}. */
  private enum Endpoint {
    SEARCH(false, "chartsFetched", 200, name -> get("/api/helm/charts/{repo}", name)),
    VERSIONS(
        false,
        "chartVersionsFetched",
        404,
        name -> get("/api/helm/charts/{repo}/{name}", name, "payments")),
    DETAIL(
        false,
        "chartDetailFetched",
        404,
        name -> get("/api/helm/charts/{repo}/{name}/{version}", name, "payments", "1.0.0")),
    TAGS(
        false,
        "chartTagsFetched",
        404,
        name -> get("/api/helm/charts/{repo}/{name}/tags", name, "payments")),
    DELETE_ALL(
        true,
        "chartDeleted",
        404,
        name -> delete("/api/helm/charts/{repo}/{name}", name, "payments")),
    DELETE_VERSION(
        true,
        "chartDeleted",
        404,
        name -> delete("/api/helm/charts/{repo}/{name}/{version}", name, "payments", "1.0.0"));

    private final boolean manage;
    private final String successMsgId;
    private final int otherTypeStatus;
    private final Function<String, MockHttpServletRequestBuilder> request;

    Endpoint(
        final boolean manage,
        final String successMsgId,
        final int otherTypeStatus,
        final Function<String, MockHttpServletRequestBuilder> request) {
      this.manage = manage;
      this.successMsgId = successMsgId;
      this.otherTypeStatus = otherTypeStatus;
      this.request = request;
    }

    MockHttpServletRequestBuilder to(final String repoName) {
      return this.request.apply(repoName);
    }
  }

  @Nested
  @DisplayName("OCI blob upload in several chunks")
  class OciBlobChunks {

    private String startBlobUpload(final Repo repo, final String token) throws Exception {
      final var it = HelmChartControllerIT.this;
      final var start =
          it.protocol(
                  post("/v2/{repo}/{name}/blobs/uploads/", repo.getName(), "payments")
                      .header(AUTHORIZATION, token))
              .andReturn()
              .getResponse();
      requireStatus(start, 202, "OCI blob upload start");
      final var location = start.getHeader("Location");
      return location.substring(location.lastIndexOf('/') + 1);
    }

    /** Sends one chunk and answers the {@code Range} header of the response. */
    private String patchBlobChunk(
        final Repo repo, final String uploadId, final byte[] chunk, final String token)
        throws Exception {
      final var it = HelmChartControllerIT.this;
      final var response =
          it.protocol(
                  patch(
                          "/v2/{repo}/{name}/blobs/uploads/{id}",
                          repo.getName(),
                          "payments",
                          uploadId)
                      .contentType(MediaType.APPLICATION_OCTET_STREAM)
                      .content(chunk)
                      .header(AUTHORIZATION, token))
              .andReturn()
              .getResponse();
      requireStatus(response, 202, "OCI blob chunk upload");
      return response.getHeader("Range");
    }

    private MockHttpServletResponse finalizeBlobUpload(
        final Repo repo,
        final String uploadId,
        final String digest,
        final byte[] body,
        final String token)
        throws Exception {
      final var it = HelmChartControllerIT.this;
      return it.protocol(
              put("/v2/{repo}/{name}/blobs/uploads/{id}", repo.getName(), "payments", uploadId)
                  .param("digest", digest)
                  .contentType(MediaType.APPLICATION_OCTET_STREAM)
                  .content(body)
                  .header(AUTHORIZATION, token))
          .andReturn()
          .getResponse();
    }

    private byte[] concat(final byte[]... chunks) {
      final var out = new ByteArrayOutputStream();
      for (final var chunk : chunks) {
        out.writeBytes(chunk);
      }
      return out.toByteArray();
    }

    /** Sends one chunk with an explicit {@code Content-Range} header. */
    private MockHttpServletResponse patchBlobChunkWithContentRange(
        final Repo repo,
        final String uploadId,
        final byte[] chunk,
        final String range,
        final String token)
        throws Exception {
      final var it = HelmChartControllerIT.this;
      return it.protocol(
              patch("/v2/{repo}/{name}/blobs/uploads/{id}", repo.getName(), "payments", uploadId)
                  .contentType(MediaType.APPLICATION_OCTET_STREAM)
                  .header("Content-Range", range)
                  .content(chunk)
                  .header(AUTHORIZATION, token))
          .andReturn()
          .getResponse();
    }

    private MockHttpServletResponse blobUploadStatus(
        final Repo repo, final String uploadId, final boolean head, final String token)
        throws Exception {
      final var it = HelmChartControllerIT.this;
      final var builder =
          head
              ? head("/v2/{repo}/{name}/blobs/uploads/{id}", repo.getName(), "payments", uploadId)
              : get("/v2/{repo}/{name}/blobs/uploads/{id}", repo.getName(), "payments", uploadId);
      return it.protocol(builder.header(AUTHORIZATION, token)).andReturn().getResponse();
    }

    @Test
    @DisplayName("a chunk whose Content-Range starts where the upload ends is appended as usual")
    void contentRangeMatchingTheCurrentSizeIsAppended() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.asProtocolBearer(it.adminBearerToken());
      final var repo = it.helmRepo();
      final var first = "first range ".repeat(20).getBytes(StandardCharsets.UTF_8);
      final var second = "second range ".repeat(20).getBytes(StandardCharsets.UTF_8);
      final var blob = this.concat(first, second);
      final var uploadId = this.startBlobUpload(repo, token);
      requireStatus(
          this.patchBlobChunkWithContentRange(
              repo, uploadId, first, "0-" + (first.length - 1), token),
          202,
          "first ranged chunk");

      final var secondResponse =
          this.patchBlobChunkWithContentRange(
              repo,
              uploadId,
              second,
              first.length + "-" + (first.length + second.length - 1),
              token);

      requireStatus(secondResponse, 202, "second ranged chunk");
      assertThat(secondResponse.getHeader("Range")).isEqualTo("0-" + (blob.length - 1));
      requireStatus(
          this.finalizeBlobUpload(repo, uploadId, sha256(blob), new byte[0], token),
          201,
          "OCI blob finalize");
      assertThat(storageDirOf(repo).resolve("oci").resolve("blobs").resolve(sha256(blob)))
          .hasBinaryContent(blob);
    }

    @Test
    @DisplayName(
        "a chunk whose Content-Range does not start where the upload ends is refused with 416")
    void contentRangeMismatchIsRefusedWith416() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.asProtocolBearer(it.adminBearerToken());
      final var repo = it.helmRepo();
      final var first = "head range ".repeat(20).getBytes(StandardCharsets.UTF_8);
      final var stray = "stray chunk ".repeat(20).getBytes(StandardCharsets.UTF_8);
      final var uploadId = this.startBlobUpload(repo, token);
      this.patchBlobChunk(repo, uploadId, first, token);

      final var response =
          this.patchBlobChunkWithContentRange(
              repo, uploadId, stray, "0-" + (stray.length - 1), token);

      assertThat(response.getStatus()).isEqualTo(416);
      assertThat(response.getHeader("Range")).isEqualTo("0-" + (first.length - 1));
      assertThat(response.getHeader("Docker-Upload-UUID")).isEqualTo(uploadId);
      assertThat(response.getHeader("Location")).isNotBlank();
      assertThat(storageDirOf(repo).resolve("oci").resolve("blobs").resolve(uploadId))
          .hasBinaryContent(first);
    }

    @Test
    @DisplayName("the upload-status endpoint answers 204 with the running Range, on GET and HEAD")
    void uploadStatusAnswersTheRunningRange() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.asProtocolBearer(it.adminBearerToken());
      final var repo = it.helmRepo();
      final var chunk = "status range ".repeat(20).getBytes(StandardCharsets.UTF_8);
      final var uploadId = this.startBlobUpload(repo, token);
      this.patchBlobChunk(repo, uploadId, chunk, token);

      final var getResponse = this.blobUploadStatus(repo, uploadId, false, token);
      final var headResponse = this.blobUploadStatus(repo, uploadId, true, token);

      assertThat(getResponse.getStatus()).isEqualTo(204);
      assertThat(getResponse.getHeader("Range")).isEqualTo("0-" + (chunk.length - 1));
      assertThat(getResponse.getHeader("Docker-Upload-UUID")).isEqualTo(uploadId);
      assertThat(getResponse.getHeader("Location")).isNotBlank();
      assertThat(headResponse.getStatus()).isEqualTo(204);
      assertThat(headResponse.getHeader("Range")).isEqualTo("0-" + (chunk.length - 1));
    }

    @Test
    @DisplayName("the upload-status endpoint answers 404 for an upload that was never started")
    void uploadStatusAnswers404ForAnUnknownSession() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.asProtocolBearer(it.adminBearerToken());
      final var repo = it.helmRepo();
      final var unknownUploadId = "00000000-0000-0000-0000-000000000099";

      final var response = this.blobUploadStatus(repo, unknownUploadId, false, token);

      assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("a blob sent in several chunks is stored whole and answers the running range")
    void blobSentInSeveralChunksIsStoredWhole() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.asProtocolBearer(it.adminBearerToken());
      final var repo = it.helmRepo();
      final var first = "first chunk ".repeat(20).getBytes(StandardCharsets.UTF_8);
      final var second = "second chunk ".repeat(30).getBytes(StandardCharsets.UTF_8);
      final var third = "third".getBytes(StandardCharsets.UTF_8);
      final var blob = this.concat(first, second, third);
      final var uploadId = this.startBlobUpload(repo, token);

      assertThat(this.patchBlobChunk(repo, uploadId, first, token))
          .isEqualTo("0-" + (first.length - 1));
      assertThat(this.patchBlobChunk(repo, uploadId, second, token))
          .isEqualTo("0-" + (first.length + second.length - 1));
      assertThat(this.patchBlobChunk(repo, uploadId, third, token))
          .isEqualTo("0-" + (blob.length - 1));
      final var finalize =
          this.finalizeBlobUpload(repo, uploadId, sha256(blob), new byte[0], token);

      requireStatus(finalize, 201, "OCI blob finalize");
      assertThat(storageDirOf(repo).resolve("oci").resolve("blobs").resolve(sha256(blob)))
          .hasBinaryContent(blob);
      assertThat(it.storedBlobs(repo)).containsExactly(sha256(blob));
      assertThat(it.blobRowExists(repo, blob)).isTrue();
      assertThat(it.netUsage(repo)).isEqualTo(blob.length);
    }

    @Test
    @DisplayName("the chunk that closes the upload in the finalize request is appended to the rest")
    void closingChunkIsAppended() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.asProtocolBearer(it.adminBearerToken());
      final var repo = it.helmRepo();
      final var head = "head ".repeat(40).getBytes(StandardCharsets.UTF_8);
      final var tail = "tail ".repeat(25).getBytes(StandardCharsets.UTF_8);
      final var blob = this.concat(head, tail);
      final var uploadId = this.startBlobUpload(repo, token);
      this.patchBlobChunk(repo, uploadId, head, token);

      final var finalize = this.finalizeBlobUpload(repo, uploadId, sha256(blob), tail, token);

      requireStatus(finalize, 201, "OCI blob finalize");
      assertThat(storageDirOf(repo).resolve("oci").resolve("blobs").resolve(sha256(blob)))
          .hasBinaryContent(blob);
      assertThat(it.netUsage(repo)).isEqualTo(blob.length);
    }

    @Test
    @DisplayName("a blob pushed again in several chunks is refunded down to the one stored copy")
    void duplicateMultiChunkBlobIsRefunded() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.asProtocolBearer(it.adminBearerToken());
      final var repo = it.helmRepo();
      final var first = "one ".repeat(50).getBytes(StandardCharsets.UTF_8);
      final var second = "two ".repeat(50).getBytes(StandardCharsets.UTF_8);
      final var blob = this.concat(first, second);

      for (int push = 0; push < 2; push++) {
        final var uploadId = this.startBlobUpload(repo, token);
        this.patchBlobChunk(repo, uploadId, first, token);
        this.patchBlobChunk(repo, uploadId, second, token);
        requireStatus(
            this.finalizeBlobUpload(repo, uploadId, sha256(blob), new byte[0], token),
            201,
            "OCI blob finalize");
      }

      assertThat(it.netUsage(repo)).isEqualTo(blob.length);
      assertThat(it.storedBlobs(repo)).containsExactly(sha256(blob));
    }

    @Test
    @DisplayName("a finalize whose digest does not match what was uploaded is refused")
    void digestMismatchIsRefused() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.asProtocolBearer(it.adminBearerToken());
      final var repo = it.helmRepo();
      final var first = "first half ".repeat(20).getBytes(StandardCharsets.UTF_8);
      final var second = "second half ".repeat(20).getBytes(StandardCharsets.UTF_8);
      final var claimed =
          sha256("what the client believes it sent".getBytes(StandardCharsets.UTF_8));
      final var uploadId = this.startBlobUpload(repo, token);
      this.patchBlobChunk(repo, uploadId, first, token);
      this.patchBlobChunk(repo, uploadId, second, token);

      final var response = this.finalizeBlobUpload(repo, uploadId, claimed, new byte[0], token);

      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(
              (String)
                  JsonPath.read(
                      response.getContentAsString(StandardCharsets.UTF_8), "$.errors[0].code"))
          .isEqualTo("DIGEST_INVALID");
      assertThat(it.storedBlobs(repo)).containsExactly(uploadId);
      assertThat(it.helmOciBlobService.findByDigest(repo.getId(), claimed)).isEmpty();
      assertThat(it.netUsage(repo)).isEqualTo(first.length + second.length);
    }

    @Test
    @DisplayName("a finalize with a malformed digest is refused")
    void malformedDigestIsRefused() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.asProtocolBearer(it.adminBearerToken());
      final var repo = it.helmRepo();
      final var blob = "some blob ".repeat(10).getBytes(StandardCharsets.UTF_8);
      final var uploadId = this.startBlobUpload(repo, token);
      this.patchBlobChunk(repo, uploadId, blob, token);

      final var response =
          this.finalizeBlobUpload(repo, uploadId, "sha256:abc", new byte[0], token);

      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(it.storedBlobs(repo)).containsExactly(uploadId);
    }
  }

  @Nested
  @DisplayName("OCI blob upload usage")
  class OciBlobUsage {

    @Test
    @DisplayName("a blob is charged when it is uploaded, in one request or in chunks")
    void blobIsChargedOnUpload() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.asProtocolBearer(it.adminBearerToken());
      final var repo = it.helmRepo();
      final var whole = "one request ".repeat(40).getBytes(StandardCharsets.UTF_8);
      final var chunked = "in chunks ".repeat(70).getBytes(StandardCharsets.UTF_8);

      it.uploadOciBlob(repo, "payments", whole, sha256(whole), token);
      assertThat(it.netUsage(repo)).isEqualTo(whole.length);

      it.uploadOciBlobChunked(repo, "payments", chunked, sha256(chunked), token);
      assertThat(it.netUsage(repo)).isEqualTo((long) whole.length + chunked.length);
    }

    @Test
    @DisplayName("uploading a digest that is already stored charges nothing more")
    void duplicateBlobIsNotChargedTwice() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.asProtocolBearer(it.adminBearerToken());
      final var repo = it.helmRepo();
      final var blob = "shared blob ".repeat(50).getBytes(StandardCharsets.UTF_8);
      it.uploadOciBlob(repo, "payments", blob, sha256(blob), token);
      clearInvocations(it.usageUpdateService);

      // Sent whole, the copy is charged and refunded inside one request, so nothing is reported.
      it.uploadOciBlob(repo, "payments", blob, sha256(blob), token);
      verifyNoInteractions(it.usageUpdateService);

      // Chunked, the chunk is charged by its own request and handed back by the finalize.
      it.uploadOciBlobChunked(repo, "payments", blob, sha256(blob), token);
      assertThat(it.netUsage(repo)).isZero();
      try (final var blobs = Files.list(storageDirOf(repo).resolve("oci").resolve("blobs"))) {
        assertThat(blobs.count()).isEqualTo(1);
      }
    }

    @Test
    @DisplayName("one chart pushed under several tags is charged once, and deleting it releases it")
    void chartUnderSeveralTagsIsChargedOnce() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var panelToken = it.adminBearerToken();
      final var token = it.asProtocolBearer(panelToken);
      final var repo = it.helmRepo();
      final var chart = archive(ChartSpec.of("payments", "1.0.0"));

      for (final var tag : List.of("1.0.0", "stable", "latest")) {
        requireStatus(it.putOciChart(repo, "payments", tag, chart, token), 201, "manifest " + tag);
      }

      // The archive is stored once whatever the tags; each tag adds its own manifest file.
      var manifests = 0L;
      for (final var tag : List.of("1.0.0", "stable", "latest")) {
        manifests += it.ociManifestFileSize(repo, "payments", tag);
      }
      assertThat(it.netUsage(repo)).isEqualTo(chart.length + manifests);

      expectDeleted(it.deleteAllRequest(repo, "payments", panelToken));

      assertThat(it.netUsage(repo)).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DeleteRoute.class)
    @DisplayName("deleting a chart deletes its config and provenance blobs and releases every byte")
    void deletingAChartReleasesItsBlobsAndManifests(final DeleteRoute route) throws Exception {
      final var it = HelmChartControllerIT.this;
      final var panelToken = it.adminBearerToken();
      final var token = it.asProtocolBearer(panelToken);
      final var repo = it.helmRepo();
      final var chart = archive(ChartSpec.of("payments", "1.0.0"));
      final var config =
          "{\"name\":\"payments\",\"version\":\"1.0.0\"}".getBytes(StandardCharsets.UTF_8);
      final var provenance =
          "-----BEGIN PGP SIGNED MESSAGE-----".repeat(4).getBytes(StandardCharsets.UTF_8);
      for (final var tag : List.of("1.0.0", "stable")) {
        requireStatus(
            it.putOciChart(repo, "payments", tag, chart, config, List.of(provenance), token),
            201,
            "manifest " + tag);
      }
      final var manifests =
          it.ociManifestFileSize(repo, "payments", "1.0.0")
              + it.ociManifestFileSize(repo, "payments", "stable");
      assertThat(it.storedBlobs(repo))
          .containsExactlyInAnyOrder(sha256(chart), sha256(config), sha256(provenance));
      assertThat(it.netUsage(repo))
          .isEqualTo(chart.length + config.length + provenance.length + manifests);

      route.perform(it, repo, panelToken);

      assertThat(it.storedBlobs(repo)).isEmpty();
      assertThat(it.ociManifestFile(repo, "payments", "1.0.0")).doesNotExist();
      assertThat(it.ociManifestFile(repo, "payments", "stable")).doesNotExist();
      assertThat(it.blobRowExists(repo, chart)).isFalse();
      assertThat(it.blobRowExists(repo, config)).isFalse();
      assertThat(it.blobRowExists(repo, provenance)).isFalse();
      assertThat(it.netUsage(repo)).isZero();
    }

    @Test
    @DisplayName("a config blob two charts share stays until the last of them is deleted")
    void sharedConfigBlobOutlivesTheFirstChart() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var panelToken = it.adminBearerToken();
      final var token = it.asProtocolBearer(panelToken);
      final var repo = it.helmRepo();
      final var first = archive(ChartSpec.of("payments", "1.0.0"));
      final var second = archive(ChartSpec.of("payments", "1.1.0"));
      final var config = "{\"name\":\"payments\"}".getBytes(StandardCharsets.UTF_8);
      requireStatus(
          it.putOciChart(repo, "payments", "1.0.0", first, config, List.of(), token), 201, "1.0.0");
      requireStatus(
          it.putOciChart(repo, "payments", "1.1.0", second, config, List.of(), token),
          201,
          "1.1.0");
      final var secondManifest = it.ociManifestFileSize(repo, "payments", "1.1.0");

      expectDeleted(it.deleteVersionRequest(repo, "payments", "1.0.0", panelToken));

      assertThat(it.storedBlobs(repo)).containsExactlyInAnyOrder(sha256(second), sha256(config));
      assertThat(it.blobRowExists(repo, config)).isTrue();
      assertThat(it.netUsage(repo)).isEqualTo(second.length + config.length + secondManifest);

      expectDeleted(it.deleteVersionRequest(repo, "payments", "1.1.0", panelToken));

      assertThat(it.storedBlobs(repo)).isEmpty();
      assertThat(it.blobRowExists(repo, config)).isFalse();
      assertThat(it.netUsage(repo)).isZero();
    }

    @Test
    @DisplayName("a config blob another chart's manifest still names is not deleted with the first")
    void configBlobOfAnotherChartSurvives() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var panelToken = it.adminBearerToken();
      final var token = it.asProtocolBearer(panelToken);
      final var repo = it.helmRepo();
      final var payments = archive(ChartSpec.of("payments", "1.0.0"));
      final var orders = archive(ChartSpec.of("orders", "1.0.0"));
      final var config = "{}".repeat(8).getBytes(StandardCharsets.UTF_8);
      requireStatus(
          it.putOciChart(repo, "payments", "1.0.0", payments, config, List.of(), token),
          201,
          "payments");
      requireStatus(
          it.putOciChart(repo, "orders", "1.0.0", orders, config, List.of(), token), 201, "orders");

      expectDeleted(it.deleteAllRequest(repo, "payments", panelToken));

      assertThat(it.storedBlobs(repo)).containsExactlyInAnyOrder(sha256(orders), sha256(config));
      assertThat(it.ociManifestFile(repo, "orders", "1.0.0")).exists();
    }
  }

  /** The three ways a chart version leaves a Helm repo. */
  private enum DeleteRoute {
    PANEL_VERSION,
    PANEL_ALL_VERSIONS,
    PROTOCOL_DELETE;

    void perform(final HelmChartControllerIT it, final Repo repo, final String panelToken)
        throws Exception {
      switch (this) {
        case PANEL_VERSION ->
            expectDeleted(it.deleteVersionRequest(repo, "payments", "1.0.0", panelToken));
        case PANEL_ALL_VERSIONS -> expectDeleted(it.deleteAllRequest(repo, "payments", panelToken));
        case PROTOCOL_DELETE ->
            requireStatus(
                it.protocol(
                        delete(
                                "/{repo}/api/charts/{name}/{version}",
                                repo.getName(),
                                "payments",
                                "1.0.0")
                            .header(AUTHORIZATION, it.asProtocolBearer(panelToken)))
                    .andReturn()
                    .getResponse(),
                200,
                "protocol chart delete");
      }
    }
  }

  @Nested
  @DisplayName("authentication, permissions and repo resolution")
  class Authentication {

    private Repo repoWithChart(final boolean privateRepo) throws Exception {
      final var it = HelmChartControllerIT.this;
      final var repo = privateRepo ? it.privateHelmRepo() : it.helmRepo();
      it.upload(repo, ChartSpec.of("payments", "1.0.0"), it.adminBearerToken());
      return repo;
    }

    private ResultActions send(final Endpoint endpoint, final String repoName, final String header)
        throws Exception {
      final var builder = endpoint.to(repoName);
      return HelmChartControllerIT.this.perform(
          header == null ? builder : builder.header(AUTHORIZATION, header));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Endpoint.class)
    @DisplayName("a private repo needs an Authorization header")
    void privateRepoWithoutHeader(final Endpoint endpoint) throws Exception {
      final var repo = this.repoWithChart(true);

      expectUnauthorized(this.send(endpoint, repo.getName(), null));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Endpoint.class)
    @DisplayName("a public repo is readable anonymously; managing it is not")
    void publicRepoWithoutHeader(final Endpoint endpoint) throws Exception {
      final var repo = this.repoWithChart(false);

      final var result = this.send(endpoint, repo.getName(), null);

      if (endpoint.manage) {
        expectUnauthorized(result);
      } else {
        expectSuccess(result, endpoint.successMsgId);
      }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Endpoint.class)
    @DisplayName("a plain user reads a private repo but cannot manage it")
    void plainUserOnPrivateRepo(final Endpoint endpoint) throws Exception {
      final var it = HelmChartControllerIT.this;
      final var repo = this.repoWithChart(true);

      final var result = this.send(endpoint, repo.getName(), it.userBearerToken());

      if (endpoint.manage) {
        expectForbidden(result);
        assertThat(it.storedVersions(repo, "payments")).containsExactly("1.0.0");
      } else {
        expectSuccess(result, endpoint.successMsgId);
      }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Endpoint.class)
    @DisplayName("an admin can use every endpoint on a private repo")
    void adminOnPrivateRepo(final Endpoint endpoint) throws Exception {
      final var it = HelmChartControllerIT.this;
      final var repo = this.repoWithChart(true);

      expectSuccess(
          this.send(endpoint, repo.getName(), it.adminBearerToken()), endpoint.successMsgId);
    }

    /** A JWT that does not verify is 401 accessNotAllowed, even for a public repo. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Endpoint.class)
    @DisplayName("a malformed bearer token is 401 accessNotAllowed")
    void malformedBearerToken(final Endpoint endpoint) throws Exception {
      final var repo = this.repoWithChart(false);

      expectError(
          this.send(endpoint, repo.getName(), "Bearer not-a-jwt"),
          HttpStatus.UNAUTHORIZED,
          "accessNotAllowed",
          "accessNotAllowed",
          "Access isn't allowed.");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Endpoint.class)
    @DisplayName("an unknown authorization scheme is 401 unAuthorized")
    void unknownScheme(final Endpoint endpoint) throws Exception {
      final var repo = this.repoWithChart(false);

      expectUnauthorized(this.send(endpoint, repo.getName(), "Token abc"));
    }

    /** RPS-927: an undecodable Basic credential is a plain 401, not a 500. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Basic !!!", "Basic dXNlcg=="})
    @DisplayName("a Basic header that is not base64 or has no colon is 401 unAuthorized")
    void undecodableBasicHeader(final String authHeader) throws Exception {
      final var repo = this.repoWithChart(false);

      expectUnauthorized(this.send(Endpoint.SEARCH, repo.getName(), authHeader));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Endpoint.class)
    @DisplayName("an expired token is 401 sessionExpired, even for a public repo")
    void expiredToken(final Endpoint endpoint) throws Exception {
      final var it = HelmChartControllerIT.this;
      final var repo = this.repoWithChart(false);
      final var expired =
          it.expiredBearerTokenFor(it.createUser(uniqueUsername("expired"), UserRole.ADMIN));

      expectError(
          this.send(endpoint, repo.getName(), expired),
          HttpStatus.UNAUTHORIZED,
          "sessionExpired",
          "sessionExpired",
          "Session expired.");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Endpoint.class)
    @DisplayName("a token whose user no longer exists is 401 unAuthorized")
    void tokenOfDeletedUser(final Endpoint endpoint) throws Exception {
      final var it = HelmChartControllerIT.this;
      final var repo = this.repoWithChart(false);
      final var ghost = it.bearerTokenFor(UUID.randomUUID(), "ghost-user");

      expectError(
          this.send(endpoint, repo.getName(), ghost),
          HttpStatus.UNAUTHORIZED,
          "unAuthorized",
          "unAuthorized",
          NO_PERMISSION_TEXT);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Endpoint.class)
    @DisplayName("an unknown repo is 404 repoNotFound with credentials, 401 without (RPS-887)")
    void unknownRepo(final Endpoint endpoint) throws Exception {
      final var it = HelmChartControllerIT.this;

      expectRepoNotFound(this.send(endpoint, "no-such-repo", it.adminBearerToken()));
      expectUnauthorized(this.send(endpoint, "no-such-repo", null));
    }

    /**
     * The endpoints do not check the repo type ({@code @RepoOperation} defaults to every scope), so
     * a repo of another type answers as an empty Helm repo: the chart list is empty, every
     * per-chart endpoint is 404 chartNotFound. Pinned as-is.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Endpoint.class)
    @DisplayName("a repo of another type behaves like an empty Helm repo")
    void repoOfAnotherType(final Endpoint endpoint) throws Exception {
      final var it = HelmChartControllerIT.this;
      final var maven = it.seedRepo(RepoType.MAVEN, uniqueRepoName("maven"));

      final var result = this.send(endpoint, maven.getName(), it.adminBearerToken());

      if (endpoint.otherTypeStatus == 200) {
        expectSuccess(result, endpoint.successMsgId);
      } else {
        expectChartNotFound(result);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Unsupported verbs and unmapped routes (RPS-849)
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("unsupported verbs and routes")
  class Routing {

    /** Pins the RPS-849 behavior: unmapped verbs and routes answer 404 itemNotFound. */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("unmappedRequests")
    @DisplayName("answers 404 itemNotFound and changes nothing")
    void unmapped(final HttpMethod method, final String path) throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      it.upload(repo, ChartSpec.of("payments", "1.0.0"), token);

      expectError(
          it.perform(request(method, path.formatted(repo.getName())).header(AUTHORIZATION, token)),
          HttpStatus.NOT_FOUND,
          "itemNotFound",
          null,
          "The requested item is not found.");

      assertThat(it.storedVersions(repo, "payments")).containsExactly("1.0.0");
    }

    static Stream<Arguments> unmappedRequests() {
      return Stream.of(
          Arguments.of(HttpMethod.POST, "/api/helm/charts/%s"),
          Arguments.of(HttpMethod.PUT, "/api/helm/charts/%s"),
          Arguments.of(HttpMethod.PATCH, "/api/helm/charts/%s"),
          Arguments.of(HttpMethod.DELETE, "/api/helm/charts/%s"),
          Arguments.of(HttpMethod.POST, "/api/helm/charts/%s/payments"),
          Arguments.of(HttpMethod.PUT, "/api/helm/charts/%s/payments"),
          Arguments.of(HttpMethod.PATCH, "/api/helm/charts/%s/payments"),
          Arguments.of(HttpMethod.POST, "/api/helm/charts/%s/payments/1.0.0"),
          Arguments.of(HttpMethod.PUT, "/api/helm/charts/%s/payments/1.0.0"),
          Arguments.of(HttpMethod.PATCH, "/api/helm/charts/%s/payments/1.0.0"),
          Arguments.of(HttpMethod.POST, "/api/helm/charts/%s/payments/tags"),
          Arguments.of(HttpMethod.PUT, "/api/helm/charts/%s/payments/tags"),
          Arguments.of(HttpMethod.GET, "/api/helm/charts/%s/payments/1.0.0/extra"),
          Arguments.of(HttpMethod.DELETE, "/api/helm/charts/%s/payments/1.0.0/extra"));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // POST /{repo}/api/charts and OCI push: Chart.yaml scalars that are not strings (RPS-928)
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("Chart.yaml scalar types on upload")
  class ChartYamlScalars {

    private static final String BASE = "name: payments\nversion: 1.0.0\n";

    private static Arguments rejected(
        final String label, final String chartYaml, final String msgId, final String text) {
      return Arguments.of(label, chartYaml, msgId, text);
    }

    static Stream<Arguments> rejectedChartYamls() {
      final var appVersionText =
          "Invalid chart appVersion: it must be a string, quote it (for example" + " \"2\").";
      final var descriptionText = "Invalid chart description: it must be a string, quote it.";
      final var yamlText = "Chart.yaml is not a valid YAML mapping.";
      return Stream.of(
          rejected(
              "integer appVersion",
              BASE + "appVersion: 2\n",
              "chartAppVersionInvalid",
              appVersionText),
          rejected(
              "float appVersion",
              BASE + "appVersion: 1.10\n",
              "chartAppVersionInvalid",
              appVersionText),
          rejected(
              "boolean appVersion",
              BASE + "appVersion: true\n",
              "chartAppVersionInvalid",
              appVersionText),
          rejected(
              "list appVersion",
              BASE + "appVersion: [1, 2]\n",
              "chartAppVersionInvalid",
              appVersionText),
          rejected(
              "integer description",
              BASE + "description: 42\n",
              "chartDescriptionInvalid",
              descriptionText),
          rejected(
              "integer type",
              BASE + "type: 3\n",
              "chartTypeInvalid",
              "Invalid chart type: it must be a string of at most 32 characters."),
          rejected(
              "integer name",
              "name: 5\nversion: 1.0.0\n",
              "chartNameInvalid",
              "Invalid chart name."),
          rejected(
              "integer version",
              "name: payments\nversion: 2\n",
              "chartVersionInvalid",
              "Invalid chart version."),
          rejected(
              "float version",
              "name: payments\nversion: 1.0\n",
              "chartVersionInvalid",
              "Invalid chart version."),
          rejected("empty document", "", "chartYamlInvalid", yamlText),
          rejected("list document", "- a\n- b\n", "chartYamlInvalid", yamlText),
          rejected(
              "malformed YAML", "name: [unclosed\nversion: 1.0.0\n", "chartYamlInvalid", yamlText),
          rejected(
              "Chart.yaml over the size limit",
              BASE + "#".repeat((int) HelmConstants.MAX_CHART_YAML_BYTES),
              "chartYamlTooLarge",
              "Chart.yaml is larger than 10 MiB."));
    }

    private ResultActions postChartYaml(final Repo repo, final String chartYaml) throws Exception {
      final var it = HelmChartControllerIT.this;
      return it.protocol(
          multipart("/{repo}/api/charts", repo.getName())
              .part(new MockPart("chart", "payments-1.0.0.tgz", archiveOf("payments", chartYaml)))
              .header(AUTHORIZATION, it.adminProtocolBearerToken()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedChartYamls")
    @DisplayName("POST /{repo}/api/charts answers 400 with a specific message, not 500")
    void uploadIsRejected(
        final String label, final String chartYaml, final String msgId, final String text)
        throws Exception {
      final var it = HelmChartControllerIT.this;
      final var repo = it.helmRepo();
      clearInvocations(it.usageUpdateService);

      expectError(this.postChartYaml(repo, chartYaml), HttpStatus.BAD_REQUEST, msgId, msgId, text);

      assertThat(it.chartRowExists(repo, "payments")).isFalse();
      verifyNoInteractions(it.usageUpdateService);
    }

    @Test
    @DisplayName("quoted scalars are strings and keep their exact value")
    void quotedScalarsAreAccepted() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var repo = it.helmRepo();
      final var chartYaml = BASE + "appVersion: \"2\"\ndescription: \"42\"\ntype: application\n";

      final var response = this.postChartYaml(repo, chartYaml).andReturn().getResponse();

      requireStatus(response, 201, "chart upload");
      final var detail = dataMap(it.detail(repo, "payments", "1.0.0", it.userBearerToken()));
      assertThat(detail)
          .containsEntry("appVersion", "2")
          .containsEntry("description", "42")
          .containsEntry("type", "application");
    }

    @Test
    @DisplayName("omitted optional scalars are still accepted")
    void omittedOptionalScalars() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var repo = it.helmRepo();

      requireStatus(this.postChartYaml(repo, BASE).andReturn().getResponse(), 201, "chart upload");

      assertThat(it.storedVersions(repo, "payments")).containsExactly("1.0.0");
    }

    @Test
    @DisplayName("an OCI push with a non-string appVersion is rejected the same way")
    void ociPushIsRejected() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var repo = it.helmRepo();
      final var bytes = archiveOf("payments", BASE + "appVersion: 2\n");

      final var push =
          it.putOciChart(repo, "payments", "1.0.0", bytes, it.adminProtocolBearerToken());

      requireStatus(push, 400, "OCI manifest push");
      final var body = push.getContentAsString(StandardCharsets.UTF_8);
      assertThat(JsonPath.<String>read(body, "$.errors[0].code")).isEqualTo("MANIFEST_INVALID");
      assertThat(JsonPath.<String>read(body, "$.errors[0].detail"))
          .isEqualTo("chartAppVersionInvalid");
      assertThat(it.chartRowExists(repo, "payments")).isFalse();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // OCI manifest push: path name vs Chart.yaml name (RPS-978)
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("OCI manifest push name validation")
  class OciPushNameValidation {

    private static final String MISMATCH_TEXT =
        "The chart name in Chart.yaml must match the name in the push path.";

    private void expectMismatch(final MockHttpServletResponse push) throws Exception {
      requireStatus(push, 400, "OCI manifest push");
      expectOciError(push, "NAME_INVALID", MISMATCH_TEXT, "chartNameMismatch");
    }

    @Test
    @DisplayName("a path name that differs from the Chart.yaml name is 400 and stores nothing")
    void differingNameIsRejected() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      final var chart = archive(ChartSpec.of("real-name", "1.0.0"));
      clearInvocations(it.usageUpdateService);

      final var push = it.putOciChart(repo, "alias", "1.0.0", chart, it.asProtocolBearer(token));

      this.expectMismatch(push);
      assertThat(it.chartRowExists(repo, "real-name")).isFalse();
      assertThat(it.ociManifestFile(repo, "alias", "1.0.0")).doesNotExist();
      expectChartNotFound(it.tagsRequest(repo, "alias", token));
      // The chart blob was uploaded before the manifest was refused and stays on disk, so it is
      // charged (RPS-977); the refused manifest push itself adds nothing.
      assertThat(it.netUsage(repo)).isEqualTo(chart.length);
    }

    @Test
    @DisplayName("a rejected push leaves the chart already stored under that name untouched")
    void rejectedPushKeepsExistingChart() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      it.pushOci(repo, "payments", "1.0.0", ChartSpec.of("payments", "1.0.0"), token);

      final var push =
          it.putOciChart(
              repo,
              "payments",
              "1.1.0",
              archive(ChartSpec.of("orders", "1.1.0")),
              it.asProtocolBearer(token));

      this.expectMismatch(push);
      assertThat(it.chartRowExists(repo, "orders")).isFalse();
      assertThat(it.storedVersions(repo, "payments")).containsExactly("1.0.0");
      assertThat(stringList(it.tags(repo, "payments", token))).containsExactly("1.0.0");
    }

    @Test
    @DisplayName("a path name that only differs in case is rejected too")
    void differingCaseIsRejected() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var repo = it.helmRepo();

      final var push =
          it.putOciChart(
              repo,
              "Payments",
              "1.0.0",
              archive(ChartSpec.of("payments", "1.0.0")),
              it.adminProtocolBearerToken());

      this.expectMismatch(push);
      assertThat(it.chartRowExists(repo, "payments")).isFalse();
    }

    @Test
    @DisplayName("a path name equal to the Chart.yaml name is accepted")
    void matchingNameIsAccepted() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();

      it.pushOci(repo, "payments", "1.0.0", ChartSpec.of("payments", "1.0.0"), token);

      assertThat(it.storedVersions(repo, "payments")).containsExactly("1.0.0");
      assertThat(stringList(it.tags(repo, "payments", token))).containsExactly("1.0.0");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // OCI endpoints: a required header/parameter that is missing answers the OCI errors[] body
  // instead of a bare status (RPS-1110)
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("OCI missing required headers")
  class OciMissingRequiredHeaders {

    @Test
    @DisplayName(
        "a manifest push with NO Content-Type header at all is refused with an OCI errors[] body,"
            + " not a bodyless 400")
    void manifestPushWithoutContentTypeIsRejected() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var repo = it.helmRepo();

      final var push =
          it.protocol(
                  put("/v2/{repo}/{name}/manifests/{tag}", repo.getName(), "payments", "1.0.0")
                      .content("{}".getBytes(StandardCharsets.UTF_8))
                      .header(AUTHORIZATION, it.adminProtocolBearerToken()))
              .andReturn()
              .getResponse();

      requireStatus(push, 400, "manifest push with no Content-Type");
      expectOciError(
          push,
          "MANIFEST_INVALID",
          "The manifest push needs a Content-Type header.",
          "manifestContentTypeMissing");
    }

    @Test
    @DisplayName(
        "a blob upload finalize with no digest query parameter is refused with an OCI errors[]"
            + " body, not a bodyless 400")
    void blobFinalizeWithoutDigestIsRejected() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var repo = it.helmRepo();
      final var token = it.adminProtocolBearerToken();

      final var start =
          it.protocol(
                  post("/v2/{repo}/{name}/blobs/uploads/", repo.getName(), "payments")
                      .header(AUTHORIZATION, token))
              .andReturn()
              .getResponse();
      requireStatus(start, 202, "OCI blob upload start");
      final var location = start.getHeader("Location");
      final var uploadId = location.substring(location.lastIndexOf('/') + 1);

      final var finalize =
          it.protocol(
                  put("/v2/{repo}/{name}/blobs/uploads/{id}", repo.getName(), "payments", uploadId)
                      .contentType(MediaType.APPLICATION_OCTET_STREAM)
                      .content("abc".getBytes(StandardCharsets.UTF_8))
                      .header(AUTHORIZATION, token))
              .andReturn()
              .getResponse();

      requireStatus(finalize, 400, "blob finalize with no digest");
      expectOciError(
          finalize, "UNSUPPORTED", "The upload needs a digest query parameter.", "digestMissing");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // OCI manifest push: malformed manifest (RPS-987)
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("OCI manifest push manifest validation")
  class OciPushManifestValidation {

    private static final String DIGEST = "sha256:" + "a".repeat(64);

    private static String layer(final String digest, final String size) {
      return "{\"layers\":[{\"digest\":%s,\"size\":%s}]}".formatted(digest, size);
    }

    private MockHttpServletResponse putManifest(final Repo repo, final String manifest)
        throws Exception {
      final var it = HelmChartControllerIT.this;
      return it.protocol(
              put("/v2/{repo}/{name}/manifests/{tag}", repo.getName(), "payments", "1.0.0")
                  .contentType(OCI_MANIFEST_TYPE)
                  .content(manifest)
                  .header(AUTHORIZATION, it.adminProtocolBearerToken()))
          .andReturn()
          .getResponse();
    }

    static Stream<Arguments> malformedManifests() {
      return Stream.of(
          Arguments.of(
              "not json", "manifestInvalidJson", "The manifest is not a valid JSON object."),
          Arguments.of("[]", "manifestInvalidJson", "The manifest is not a valid JSON object."),
          Arguments.of(
              "{}", "manifestLayersMissing", "The manifest must have a non-empty layers array."),
          Arguments.of(
              "{\"layers\":[]}",
              "manifestLayersMissing",
              "The manifest must have a non-empty layers array."),
          Arguments.of(
              "{\"layers\":[{\"size\":10}]}",
              "manifestLayerInvalid",
              "The first layer of the manifest needs a sha256 digest and a numeric size."),
          Arguments.of(
              "{\"layers\":[{\"digest\":\"" + DIGEST + "\"}]}",
              "manifestLayerInvalid",
              "The first layer of the manifest needs a sha256 digest and a numeric size."),
          Arguments.of(
              layer("\"" + DIGEST + "\"", "\"10\""),
              "manifestLayerInvalid",
              "The first layer of the manifest needs a sha256 digest and a numeric size."),
          Arguments.of(
              layer("\"../../etc/passwd\"", "10"),
              "manifestLayerInvalid",
              "The first layer of the manifest needs a sha256 digest and a numeric size."));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedManifests")
    @DisplayName("a malformed manifest is 400 with a specific msgId and stores nothing")
    void malformedManifestIsRejected(final String manifest, final String msgId, final String text)
        throws Exception {
      final var it = HelmChartControllerIT.this;
      final var repo = it.helmRepo();

      final var push = this.putManifest(repo, manifest);

      requireStatus(push, 400, "OCI manifest push");
      expectOciError(push, "MANIFEST_INVALID", text, msgId);
      assertThat(it.chartRowExists(repo, "payments")).isFalse();
      assertThat(it.ociManifestFile(repo, "payments", "1.0.0")).doesNotExist();
    }

    @Test
    @DisplayName("a layer whose blob was never uploaded is still 404 blobNotFound")
    void missingBlobIsStillNotFound() throws Exception {
      final var repo = HelmChartControllerIT.this.helmRepo();

      final var push = this.putManifest(repo, layer("\"" + DIGEST + "\"", "10"));

      requireStatus(push, 404, "OCI manifest push");
      expectOciError(push, "MANIFEST_BLOB_UNKNOWN", "blobNotFound", "blobNotFound");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // OCI blob finalize (RPS-929)
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("OCI blob finalize")
  class OciBlobFinalize {

    /**
     * Clients that do not HEAD first, and concurrent pushes of the same layer, finalize a digest
     * that is already stored. The registry has to accept it (RPS-929).
     */
    @Test
    @DisplayName("accepts a blob whose digest already exists and keeps a single copy")
    void acceptsExistingDigest() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminProtocolBearerToken();
      final var repo = it.helmRepo();
      final var bytes = archive(ChartSpec.of("payments", "1.0.0"));
      final var digest = sha256(bytes);

      it.uploadOciBlob(repo, "payments", bytes, digest, token);
      it.uploadOciBlob(repo, "payments", bytes, digest, token);

      final var blobs = storageDirOf(repo).resolve("oci").resolve("blobs");
      try (final var stored = Files.list(blobs)) {
        assertThat(stored.map(path -> path.getFileName().toString())).containsExactly(digest);
      }
      assertThat(blobs.resolve(digest)).hasBinaryContent(bytes);
    }
  }
}
