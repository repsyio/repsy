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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.jayway.jsonpath.JsonPath;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.helm.shared.chart.repositories.HelmChartRepository;
import io.repsy.os.server.protocols.helm.shared.chart.services.HelmChartService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartForm;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

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

  private static final int PROTOCOL_PORT = 9090;
  private static final String OCI_MANIFEST_TYPE = "application/vnd.oci.image.manifest.v1+json";
  private static final String OCI_CONFIG_TYPE = "application/vnd.cncf.helm.config.v1+json";
  private static final String OCI_LAYER_TYPE =
      "application/vnd.cncf.helm.chart.content.v1.tar+gzip";
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
  @Autowired private HelmChartRepository helmChartRepository;

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

  /**
   * Protocol requests (uploads, OCI push, {@code index.yaml}) are served by the protocol router on
   * the main port (9090), which resolves the repo from the servlet path.
   */
  private static RequestPostProcessor protocolPort() {
    return request -> {
      request.setLocalPort(PROTOCOL_PORT);
      request.setServletPath(request.getRequestURI());
      return request;
    };
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
    final var layerDigest = sha256(bytes);
    final var configBytes = "{}".getBytes(StandardCharsets.UTF_8);

    this.uploadOciBlob(repo, ociName, bytes, layerDigest, token);

    final var manifest =
        ("{\"schemaVersion\":2,\"mediaType\":\"%s\",\"config\":{\"mediaType\":\"%s\","
                + "\"digest\":\"%s\",\"size\":%d},\"layers\":[{\"mediaType\":\"%s\","
                + "\"digest\":\"%s\",\"size\":%d}]}")
            .formatted(
                OCI_MANIFEST_TYPE,
                OCI_CONFIG_TYPE,
                sha256(configBytes),
                configBytes.length,
                OCI_LAYER_TYPE,
                layerDigest,
                bytes.length);
    return this.protocol(
            put("/v2/{repo}/{name}/manifests/{tag}", repo.getName(), ociName, tag)
                .contentType(OCI_MANIFEST_TYPE)
                .content(manifest)
                .header(AUTHORIZATION, token))
        .andReturn()
        .getResponse();
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

  private Path chartFile(final Repo repo, final String name, final String version) {
    return storageDirOf(repo).resolve("charts").resolve(name + "-" + version + ".tgz");
  }

  private Path ociManifestFile(final Repo repo, final String name, final String reference) {
    return storageDirOf(repo).resolve("oci").resolve("manifests").resolve(name).resolve(reference);
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
    expectError(result, HttpStatus.NOT_FOUND, "chartNotFound", "chartNotFound", "chartNotFound");
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
     * The OCI name comes from the push path and is not checked against {@code Chart.yaml}, so tags
     * can exist under a name no chart carries. They are still listed rather than answered 404.
     */
    @Test
    @DisplayName("tags pushed under a name that differs from the chart name are listed")
    void listsTagsOfNameDifferingFromChartName() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var token = it.adminBearerToken();
      final var repo = it.helmRepo();
      it.pushOci(repo, "alias", "1.0.0", ChartSpec.of("real-name", "1.0.0"), token);

      assertThat(stringList(it.tags(repo, "alias", token))).containsExactly("1.0.0");
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
    @DisplayName("a caller without MANAGE (a plain user or anonymous) changes nothing")
    void requiresManage() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var repo = it.helmRepo();
      it.upload(repo, ChartSpec.of("payments", "1.0.0"), it.adminBearerToken());

      expectUnauthorized(it.deleteVersionRequest(repo, "payments", "1.0.0", it.userBearerToken()));
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
    @DisplayName("a caller without MANAGE (a plain user or anonymous) changes nothing")
    void requiresManage() throws Exception {
      final var it = HelmChartControllerIT.this;
      final var repo = it.helmRepo();
      final var admin = it.adminBearerToken();
      it.upload(repo, ChartSpec.of("payments", "1.0.0"), admin);
      it.upload(repo, ChartSpec.of("payments", "1.1.0"), admin);

      expectUnauthorized(it.deleteAllRequest(repo, "payments", it.userBearerToken()));
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
        expectUnauthorized(result);
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
    @DisplayName("a token whose user no longer exists is 404 userNotFound")
    void tokenOfDeletedUser(final Endpoint endpoint) throws Exception {
      final var it = HelmChartControllerIT.this;
      final var repo = this.repoWithChart(false);
      final var ghost = it.bearerTokenFor(UUID.randomUUID(), "ghost-user");

      expectError(
          this.send(endpoint, repo.getName(), ghost),
          HttpStatus.NOT_FOUND,
          "userNotFound",
          "userNotFound",
          "User not found.");
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
              "Invalid chart type: it must be a string."),
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
              "malformed YAML", "name: [unclosed\nversion: 1.0.0\n", "chartYamlInvalid", yamlText));
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
      assertThat(push.getContentAsString(StandardCharsets.UTF_8))
          .contains("\"msgId\":\"chartAppVersionInvalid\"");
      assertThat(it.chartRowExists(repo, "payments")).isFalse();
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
