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
package io.repsy.os.server.protocols.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIT;
import io.repsy.os.server.protocols.helm.HelmChartFixtures;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.User;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins the wire of every OCI distribution route of Docker and Helm OCI (RPS-2058) through the full
 * router path: status, the OCI headers ({@code Location}, {@code Range}, {@code
 * Docker-Upload-UUID}, {@code Docker-Content-Digest}, {@code Content-Type}, {@code Content-Length},
 * {@code Link}, {@code WWW-Authenticate}) and the body, including the OCI error body {@code
 * OciErrorBodyAdvice} writes.
 *
 * <p>Each format runs the same request script; the responses are written to a transcript whose
 * volatile parts (repo names, upload ids, digests) are replaced by stable placeholders, and the
 * transcript must equal the one recorded on the code before the shared OCI module. A difference is
 * a wire change: the actual transcript is written to {@code target/oci-wire/} to diff against.
 *
 * <p>The script covers the OCI conformance behaviours this registry implements its own way: the
 * plain {@code <start>-<end>} {@code Content-Range} parser (a range that does not start at the
 * current upload size is a 416, a header it cannot parse, such as {@code bytes 11-11/12}, falls
 * back to appending), and a cross-repo mount request ({@code ?mount=&from=}), which is answered as
 * a plain upload start (202 and a new session), never a 201 mount.
 */
@DisplayName("OCI wire of Docker and Helm OCI")
class OciWireCharacterizationIT extends AbstractIT {

  private static final String NAME = "app";
  private static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";
  private static final String DOCKER_CONFIG = "application/vnd.oci.image.config.v1+json";
  private static final String DOCKER_LAYER = "application/vnd.oci.image.layer.v1.tar+gzip";

  private static final List<String> HEADERS =
      List.of(
          "Location",
          "Range",
          "Docker-Upload-UUID",
          "Docker-Content-Digest",
          "Docker-Distribution-API-Version",
          "Content-Type",
          "Content-Length",
          "Link",
          "WWW-Authenticate");

  private static final Pattern UUID_PATTERN =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private static final Pattern DIGEST_PATTERN = Pattern.compile("sha(256|512):[0-9a-f]{64,128}");

  /** {@code @Async}, so it cannot see this class's uncommitted rows. */
  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private WebApplicationContext webApplicationContext;

  @Test
  @DisplayName("every Docker registry route answers as recorded")
  void dockerWire() throws Exception {
    final var config = bytes("{\"architecture\":\"amd64\",\"os\":\"linux\"}");
    final var transcript = this.script(RepoType.DOCKER, config, DOCKER_CONFIG, DOCKER_LAYER);

    assertTranscript("docker", transcript, OciWireTranscripts.DOCKER);
  }

  @Test
  @DisplayName("every Helm OCI route answers as recorded")
  void helmWire() throws Exception {
    final var transcript =
        this.script(
            RepoType.HELM,
            bytes("{}"),
            HelmChartFixtures.OCI_CONFIG_TYPE,
            HelmChartFixtures.OCI_LAYER_TYPE);

    assertTranscript("helm", transcript, OciWireTranscripts.HELM);
  }

  private static void assertTranscript(
      final String format, final String actual, final String expected) throws IOException {

    final var dir = Path.of("target", "oci-wire");
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(format + ".txt"), actual, StandardCharsets.UTF_8);

    assertThat(actual).as("the %s OCI wire transcript", format).isEqualTo(expected);
  }

  private String script(
      final RepoType type, final byte[] config, final String configType, final String layerType)
      throws Exception {

    final var knownRepoIds = this.repoRepository.findAll().stream().map(Repo::getId).toList();
    final var knownUserIds = this.userRepository.findAll().stream().map(User::getId).toList();

    try {
      return this.run(type, config, configType, layerType);
    } finally {
      this.deleteCommittedRows(knownRepoIds, knownUserIds);
    }
  }

  /**
   * The rows the script committed (see {@link #commitUploads}) are deleted here: the test
   * transaction's rollback does not take them back. The repo delete cascades to its layers,
   * manifests and images.
   */
  private void deleteCommittedRows(final List<UUID> knownRepoIds, final List<UUID> knownUserIds) {

    if (TestTransaction.isActive()) {
      TestTransaction.end();
    }

    this.repoRepository.findAll().stream()
        .map(Repo::getId)
        .filter(id -> !knownRepoIds.contains(id))
        .forEach(id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.deleteCommittedUsers(
        this.userRepository.findAll().stream()
            .map(User::getId)
            .filter(id -> !knownUserIds.contains(id))
            .toList());

    // The test framework ends the transaction after the method: give it one to roll back.
    TestTransaction.start();
  }

  /**
   * Commits the repos, the users and the uploaded blobs, then opens a new test transaction.
   *
   * <p>{@code DockerProtocolTxFacade.getLayer} runs {@code NOT_SUPPORTED}: it suspends the test
   * transaction, reads on its own connection and so cannot see a layer row this test has not
   * committed, which answered the blob pull 404 {@code layerNotFound} while the blob check, which
   * runs inside the transaction, answered 200 (RPS-2165). A real registry commits the layer when
   * the upload is finalized; this is the same for the harness.
   */
  private void commitUploads() {
    this.entityManager.flush();
    TestTransaction.flagForCommit();
    TestTransaction.end();
    TestTransaction.start();
  }

  private String run(
      final RepoType type, final byte[] config, final String configType, final String layerType)
      throws Exception {

    final var repo = this.seedRepo(type, uniqueRepoName(type.name().toLowerCase()));
    final var other = this.seedRepo(type, uniqueRepoName(type.name().toLowerCase()));
    final var wire = new Wire(repo.getName(), other.getName(), this.adminProtocolBearerToken());
    final var base = "/v2/" + repo.getName() + "/" + NAME;

    final var chunked = bytes("hello world!");
    final var chunkedDigest = HelmChartFixtures.digest("SHA-256", chunked);
    final var layer =
        type == RepoType.HELM
            ? chart(HelmChartFixtures.chartYaml(NAME, "1.0.0", null, null))
            : bytes("docker-layer");
    final var layerDigest = HelmChartFixtures.digest("SHA-256", layer);
    final var configDigest = HelmChartFixtures.digest("SHA-256", config);
    final var missingDigest = "sha256:" + "0".repeat(64);
    final var unknownUpload = "00000000-0000-4000-8000-000000000000";

    wire.call("ping GET", get("/v2/"));
    wire.call("ping HEAD", head("/v2/"));
    wire.anonymous("upload start without credentials", post(base + "/blobs/uploads/"));

    wire.call("blob check of a missing blob", head(base + "/blobs/" + missingDigest));
    wire.call("blob pull of a missing blob", get(base + "/blobs/" + missingDigest));
    wire.call("blob pull of a malformed digest", get(base + "/blobs/sha256:abc"));

    wire.call(
        "upload start with an unsupported digest-algorithm",
        post(base + "/blobs/uploads/").param("digest-algorithm", "md5"));
    wire.call(
        "cross-repo mount of a missing blob",
        post(base + "/blobs/uploads/")
            .param("mount", missingDigest)
            .param("from", other.getName() + "/" + NAME));
    wire.call("status of an unknown upload", get(base + "/blobs/uploads/" + unknownUpload));
    wire.call(
        "finalize of an unknown upload",
        put(base + "/blobs/uploads/" + unknownUpload).param("digest", chunkedDigest));

    // Chunked upload with the registry's own Content-Range parser.
    final var session = wire.startUpload("chunked upload start", base);
    final var uploadPath = base + "/blobs/uploads/" + session;
    wire.call("chunk without Content-Range", octets(patch(uploadPath), "hello "));
    wire.call(
        "chunk whose Content-Range starts at the upload size",
        octets(patch(uploadPath), "world").header("Content-Range", "6-10"));
    wire.call(
        "chunk whose Content-Range does not start at the upload size",
        octets(patch(uploadPath), "xx").header("Content-Range", "0-1"));
    wire.call(
        "chunk with a Content-Range the parser cannot read is appended",
        octets(patch(uploadPath), "!").header("Content-Range", "bytes 11-11/12"));
    wire.call("upload status GET", get(uploadPath));
    wire.call("upload status HEAD", head(uploadPath));
    wire.call("finalize without digest", put(uploadPath));
    wire.call("finalize", put(uploadPath).param("digest", chunkedDigest));

    final var mismatch = wire.startUpload("upload start for a digest mismatch", base);
    wire.call(
        "finalize whose digest does not match the bytes",
        octets(put(base + "/blobs/uploads/" + mismatch), "zzz").param("digest", chunkedDigest));

    final var sha512 = wire.startUpload("upload start for a sha512 digest", base);
    final var sha512Bytes = bytes("sha512-blob");
    wire.call(
        "monolithic finalize with a sha512 digest",
        octets(put(base + "/blobs/uploads/" + sha512), sha512Bytes)
            .param("digest", HelmChartFixtures.digest("SHA-512", sha512Bytes)));

    final var configUpload = wire.startUpload("upload start for the config", base);
    wire.call(
        "monolithic finalize of the config",
        octets(put(base + "/blobs/uploads/" + configUpload), config).param("digest", configDigest));
    final var layerUpload = wire.startUpload("upload start for the layer", base);
    wire.call(
        "monolithic finalize of the layer",
        octets(put(base + "/blobs/uploads/" + layerUpload), layer).param("digest", layerDigest));

    wire.call(
        "cross-repo mount of a blob the source repo lacks",
        post("/v2/" + other.getName() + "/" + NAME + "/blobs/uploads/")
            .param("mount", chunkedDigest)
            .param("from", repo.getName() + "/" + NAME));

    if (type == RepoType.DOCKER) {
      // Helm OCI has no NOT_SUPPORTED read; its script also leaves the transaction rollback-only.
      this.commitUploads();
    }
    wire.call("blob check", head(base + "/blobs/" + chunkedDigest));
    wire.call("blob pull", get(base + "/blobs/" + chunkedDigest));
    wire.call("blob check of the layer", head(base + "/blobs/" + layerDigest));

    final var manifest =
        ("{\"schemaVersion\":2,\"mediaType\":\"%s\",\"config\":{\"mediaType\":\"%s\","
                + "\"digest\":\"%s\",\"size\":%d},\"layers\":[{\"mediaType\":\"%s\","
                + "\"digest\":\"%s\",\"size\":%d}]}")
            .formatted(
                OCI_MANIFEST,
                configType,
                configDigest,
                config.length,
                layerType,
                layerDigest,
                layer.length);
    final var manifestDigest = HelmChartFixtures.digest("SHA-256", bytes(manifest));

    wire.call("manifest check of a missing tag", head(base + "/manifests/1.0.0"));
    wire.call("manifest pull of a missing tag", get(base + "/manifests/1.0.0"));
    wire.unfiltered(
        "manifest push by tag",
        put(base + "/manifests/1.0.0").contentType(OCI_MANIFEST).content(bytes(manifest)));
    wire.unfiltered(
        "manifest push by digest",
        put(base + "/manifests/" + manifestDigest)
            .contentType(OCI_MANIFEST)
            .content(bytes(manifest)));
    wire.unfiltered(
        "manifest push by a second tag",
        put(base + "/manifests/latest").contentType(OCI_MANIFEST).content(bytes(manifest)));
    wire.call("blob pull of the layer", get(base + "/blobs/" + layerDigest));
    wire.call("manifest check by tag", head(base + "/manifests/1.0.0"));
    wire.call("manifest check by digest", head(base + "/manifests/" + manifestDigest));
    wire.call("manifest pull by tag", get(base + "/manifests/1.0.0"));
    wire.call("manifest pull by digest", get(base + "/manifests/" + manifestDigest));
    wire.call(
        "manifest pull with an Accept header naming no manifest type",
        get(base + "/manifests/1.0.0").header("Accept", "text/plain"));
    wire.call(
        "manifest pull accepting the OCI manifest type",
        get(base + "/manifests/1.0.0").header("Accept", OCI_MANIFEST));
    wire.call("manifest check of a missing digest", head(base + "/manifests/" + missingDigest));

    wire.call("tags list", get(base + "/tags/list"));
    wire.call("tags list with n=1", get(base + "/tags/list").param("n", "1"));
    wire.call("tags list with n=-1", get(base + "/tags/list").param("n", "-1"));
    wire.call("tags list of an unknown name", get("/v2/" + repo.getName() + "/nothing/tags/list"));
    wire.anonymous("tags list without credentials", get(base + "/tags/list"));

    wire.call("unknown route under a name", get(base + "/unknown"));
    wire.call("manifest delete by digest", delete(base + "/manifests/" + manifestDigest));
    wire.call("manifest pull after the delete", get(base + "/manifests/" + manifestDigest));

    return wire.transcript();
  }

  /**
   * The chart as {@link HelmChartFixtures#archive} builds it, but with a fixed modification time,
   * so its size and digest are the same on every run.
   */
  private static byte[] chart(final String chartYaml) throws IOException {
    final var bytes = new ByteArrayOutputStream();
    try (final var gzip = new GZIPOutputStream(bytes);
        final var tar = new TarArchiveOutputStream(gzip)) {
      final var data = bytes(chartYaml);
      final var entry = new TarArchiveEntry("chart/Chart.yaml");

      entry.setSize(data.length);
      entry.setModTime(0);
      tar.putArchiveEntry(entry);
      tar.write(data);
      tar.closeArchiveEntry();
    }

    return bytes.toByteArray();
  }

  private static byte[] bytes(final String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static MockHttpServletRequestBuilder octets(
      final MockHttpServletRequestBuilder request, final String content) {

    return octets(request, bytes(content));
  }

  private static MockHttpServletRequestBuilder octets(
      final MockHttpServletRequestBuilder request, final byte[] content) {

    return request.contentType("application/octet-stream").content(content);
  }

  /** Sends the script's requests and records each exchange with stable placeholders. */
  private final class Wire {

    private final String repoName;
    private final String otherName;
    private final String token;
    private final MockMvc unfilteredMockMvc;
    private final StringBuilder transcript = new StringBuilder();
    private final Map<String, String> placeholders = new LinkedHashMap<>();

    private Wire(final String repoName, final String otherName, final String token) {
      this.repoName = repoName;
      this.otherName = otherName;
      this.token = token;
      this.unfilteredMockMvc =
          MockMvcBuilders.webAppContextSetup(OciWireCharacterizationIT.this.webApplicationContext)
              .build();
    }

    void call(final String label, final MockHttpServletRequestBuilder request) throws Exception {
      this.record(label, OciWireCharacterizationIT.this.mockMvc, request, true);
    }

    void anonymous(final String label, final MockHttpServletRequestBuilder request)
        throws Exception {
      this.record(label, OciWireCharacterizationIT.this.mockMvc, request, false);
    }

    /**
     * A manifest goes through a {@link MockMvc} without the servlet filters, as {@code DockerWire}
     * explains: the character-encoding filter would append a charset no real client sends.
     */
    void unfiltered(final String label, final MockHttpServletRequestBuilder request)
        throws Exception {
      this.record(label, this.unfilteredMockMvc, request, true);
    }

    String startUpload(final String label, final String base) throws Exception {
      final var response =
          this.record(
              label, OciWireCharacterizationIT.this.mockMvc, post(base + "/blobs/uploads/"), true);
      assertThat(response.getStatus()).as(label).isEqualTo(202);

      final var location = response.getHeader("Location");
      return location.substring(location.lastIndexOf('/') + 1);
    }

    private MockHttpServletResponse record(
        final String label,
        final MockMvc mockMvc,
        final MockHttpServletRequestBuilder request,
        final boolean authenticated)
        throws Exception {

      final var builder = authenticated ? request.header(AUTHORIZATION, this.token) : request;
      final var result = mockMvc.perform(builder.with(protocolPort())).andReturn();
      final var sent = result.getRequest();
      final var response = result.getResponse();
      final var query = sent.getQueryString() == null ? "" : "?" + sent.getQueryString();

      this.transcript.append("## ").append(label).append('\n');
      this.transcript
          .append("> ")
          .append(sent.getMethod())
          .append(' ')
          .append(this.normalize(sent.getRequestURI() + query))
          .append('\n');
      this.transcript.append("< ").append(response.getStatus()).append('\n');

      for (final var header : HEADERS) {
        for (final var value : response.getHeaders(header)) {
          this.transcript
              .append("< ")
              .append(header)
              .append(": ")
              .append(this.normalize(value))
              .append('\n');
        }
      }

      final var body = response.getContentAsByteArray();
      if (body.length > 0) {
        this.transcript.append("< body: ").append(this.bodyOf(body)).append('\n');
      }

      return response;
    }

    /** A text body as it is; a binary one (a chart) by its size and digest. */
    private String bodyOf(final byte[] body) {
      final var text = new String(body, StandardCharsets.UTF_8);

      if (text.chars().anyMatch(c -> c < 0x20 && c != '\n' && c != '\t')) {
        return this.normalize(
            "<%d bytes, %s>".formatted(body.length, HelmChartFixtures.digest("SHA-256", body)));
      }

      return this.normalize(text);
    }

    private String normalize(final String text) {
      var normalized = text.replace(this.repoName, "{repo}").replace(this.otherName, "{other}");
      normalized = this.replaceAll(DIGEST_PATTERN, normalized, "digest");
      return this.replaceAll(UUID_PATTERN, normalized, "uuid");
    }

    private String replaceAll(final Pattern pattern, final String text, final String kind) {
      return pattern
          .matcher(text)
          .replaceAll(
              match ->
                  this.placeholders.computeIfAbsent(
                      kind + ":" + match.group(),
                      _ -> "{" + kind + "-" + (this.countOf(kind) + 1) + "}"));
    }

    private long countOf(final String kind) {
      return this.placeholders.keySet().stream().filter(key -> key.startsWith(kind + ":")).count();
    }

    String transcript() {
      return this.transcript.toString();
    }
  }
}
