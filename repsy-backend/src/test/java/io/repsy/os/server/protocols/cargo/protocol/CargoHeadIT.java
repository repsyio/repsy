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
package io.repsy.os.server.protocols.cargo.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Transactional;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.annotation.Propagation;

/**
 * RPS-2089: {@code HEAD} on Cargo routes must work correctly for clients that check existence
 * before downloading. Routes tested: {@code HEAD /{repo}/config.json}, {@code HEAD
 * /{repo}/<prefix>/<name>}, {@code HEAD /api/v1/crates/{name}/{version}/download}.
 */
@DisplayName("Cargo HEAD routes (RPS-2089)")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CargoHeadIT extends AbstractIntegrationTest {

  private static final String PUBLISH_PATH = "/{repo}/api/v1/crates/new";
  private static final String CRATE_NAME = "test-crate";

  private Repo cargoRepo() {
    return this.seedRepo(RepoType.CARGO, uniqueRepoName("cargo"));
  }

  private Repo privateCargoRepo() {
    return this.seedRepo(RepoType.CARGO, uniqueRepoName("cargo"), true, null);
  }

  // ----
  // Helpers
  // ----

  private static byte[] u32le(final int value) {
    return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
  }

  /** A well-formed publish body: JSON metadata, then a real gzipped-tar {@code .crate}. */
  private static byte[] publishBody(final String name, final String vers, final String manifest) {
    final var metadata =
        ("{\"name\":\"%s\",\"vers\":\"%s\",\"deps\":[],\"features\":{},\"authors\":[],"
                + "\"description\":\"RPS-2089 fixture\",\"license\":\"MIT\"}")
            .formatted(name, vers)
            .getBytes(StandardCharsets.UTF_8);
    final var crate = crateArchive(name, vers, manifest);
    final var out = new ByteArrayOutputStream();

    out.writeBytes(u32le(metadata.length));
    out.writeBytes(metadata);
    out.writeBytes(u32le(crate.length));
    out.writeBytes(crate);

    return out.toByteArray();
  }

  /** A minimal {@code .crate}: a gzipped tarball holding a manifest and a library root. */
  private static byte[] crateArchive(final String name, final String vers, final String manifest) {
    final var bytes = new ByteArrayOutputStream();

    try (final var tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bytes))) {
      addEntry(tar, name + "-" + vers + "/Cargo.toml", manifest);
      addEntry(tar, name + "-" + vers + "/src/lib.rs", "");
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    return bytes.toByteArray();
  }

  private static void addEntry(
      final TarArchiveOutputStream tar, final String name, final String content)
      throws IOException {
    final var data = content.getBytes(StandardCharsets.UTF_8);
    final var entry = new TarArchiveEntry(name);

    entry.setSize(data.length);
    tar.putArchiveEntry(entry);
    tar.write(data);
    tar.closeArchiveEntry();
  }

  private void publish(final Repo repo, final String name, final String vers, final String manifest)
      throws Exception {
    final var result =
        this.mockMvc
            .perform(
                put(PUBLISH_PATH, repo.getName())
                    .with(protocolPort())
                    .header(AUTHORIZATION, this.adminProtocolBearerToken())
                    .content(publishBody(name, vers, manifest)))
            .andReturn();
    assertThat(result.getResponse().getStatus())
        .as(result.getResponse().getContentAsString())
        .isEqualTo(200);
  }

  private MockHttpServletResponse headConfigJson(final Repo repo, final String token)
      throws Exception {
    return this.mockMvc
        .perform(
            head("/{repo}/config.json", repo.getName())
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse getConfigJson(final Repo repo, final String token)
      throws Exception {
    return this.mockMvc
        .perform(
            get("/{repo}/config.json", repo.getName())
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse headIndexPath(
      final Repo repo, final String prefix, final String name, final String token)
      throws Exception {
    return this.mockMvc
        .perform(
            head("/{repo}/{prefix}/{name}", repo.getName(), prefix, name)
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse headDownload(
      final Repo repo, final String name, final String version, final String token)
      throws Exception {
    return this.mockMvc
        .perform(
            head("/api/v1/crates/{name}/{version}/download", name, version)
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse getDownload(
      final Repo repo, final String name, final String version, final String token)
      throws Exception {
    return this.mockMvc
        .perform(
            get("/api/v1/crates/{name}/{version}/download", name, version)
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  // ----
  // Tests
  // ----

  @Test
  @DisplayName("HEAD config.json mirrors GET config.json status and headers, empty body")
  void headConfigJsonMirrorsGet() throws Exception {
    final var repo = this.cargoRepo();
    final var token = this.adminProtocolBearerToken();
    final var manifest = "[package]\nname = \"" + CRATE_NAME + "\"\n";
    this.publish(repo, CRATE_NAME, "1.0.0", manifest);

    final var head = this.headConfigJson(repo, token);
    final var get = this.getConfigJson(repo, token);

    assertThat(head.getStatus()).isEqualTo(200);
    assertThat(head.getContentAsByteArray()).isEmpty();

    assertThat(head.getStatus()).isEqualTo(get.getStatus());
    assertThat(head.getHeader("Content-Type")).isEqualTo(get.getHeader("Content-Type"));
    assertThat(head.getHeader("Content-Length")).isEqualTo(get.getHeader("Content-Length"));
  }

  @Test
  @DisplayName("HEAD config.json of unknown repo is 404")
  void headConfigJsonUnknownRepoIs404() throws Exception {
    final var repo = this.cargoRepo();
    final var token = this.adminProtocolBearerToken();

    final var result =
        this.mockMvc
            .perform(
                head("/{repo}/config.json", "unknown-repo")
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(result.getStatus()).isEqualTo(404);
  }

  @Test
  @DisplayName("HEAD config.json of private repo without credentials is 401")
  void headConfigJsonPrivateRepoIs401() throws Exception {
    final var repo = this.privateCargoRepo();
    final var token = this.adminProtocolBearerToken();
    final var manifest = "[package]\nname = \"" + CRATE_NAME + "\"\n";
    this.publish(repo, CRATE_NAME, "1.0.0", manifest);

    final var withoutCreds =
        this.mockMvc
            .perform(head("/{repo}/config.json", repo.getName()).with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(withoutCreds.getStatus()).isEqualTo(401);

    final var withCreds = this.headConfigJson(repo, token);
    assertThat(withCreds.getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("HEAD index path mirrors GET status and headers")
  void headIndexPathMirrorsGet() throws Exception {
    final var repo = this.cargoRepo();
    final var token = this.adminProtocolBearerToken();
    final var manifest = "[package]\nname = \"" + CRATE_NAME + "\"\n";
    this.publish(repo, CRATE_NAME, "1.0.0", manifest);

    // The index path for test-crate is /te/st
    final var head = this.headIndexPath(repo, "te", "st", token);
    final var get =
        this.mockMvc
            .perform(
                get("/{repo}/{prefix}/{name}", repo.getName(), "te", "st")
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(head.getStatus()).isEqualTo(get.getStatus());
    assertThat(head.getHeader("Content-Type")).isEqualTo(get.getHeader("Content-Type"));
    assertThat(head.getHeader("Content-Length")).isEqualTo(get.getHeader("Content-Length"));
  }

  @Test
  @DisplayName("HEAD download mirrors GET status and headers")
  void headDownloadMirrorsGet() throws Exception {
    final var repo = this.cargoRepo();
    final var token = this.adminProtocolBearerToken();
    final var manifest = "[package]\nname = \"" + CRATE_NAME + "\"\n";
    this.publish(repo, CRATE_NAME, "1.0.0", manifest);

    final var head = this.headDownload(repo, CRATE_NAME, "1.0.0", token);
    final var get = this.getDownload(repo, CRATE_NAME, "1.0.0", token);

    assertThat(head.getStatus()).isEqualTo(200);
    assertThat(head.getContentAsByteArray()).isEmpty();

    assertThat(head.getStatus()).isEqualTo(get.getStatus());
    assertThat(head.getHeader("Content-Type")).isEqualTo(get.getHeader("Content-Type"));
    assertThat(head.getHeader("Content-Length")).isEqualTo(get.getHeader("Content-Length"));
  }

  @Test
  @DisplayName("HEAD download of unknown version is 404")
  void headDownloadUnknownVersionIs404() throws Exception {
    final var repo = this.cargoRepo();
    final var token = this.adminProtocolBearerToken();
    final var manifest = "[package]\nname = \"" + CRATE_NAME + "\"\n";
    this.publish(repo, CRATE_NAME, "1.0.0", manifest);

    final var result =
        this.mockMvc
            .perform(
                head("/api/v1/crates/{name}/{version}/download", CRATE_NAME, "2.0.0")
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(result.getStatus()).isEqualTo(404);
  }

  @Test
  @DisplayName("HEAD download of private repo without credentials is 401")
  void headDownloadPrivateRepoIs401() throws Exception {
    final var repo = this.privateCargoRepo();
    final var token = this.adminProtocolBearerToken();
    final var manifest = "[package]\nname = \"" + CRATE_NAME + "\"\n";
    this.publish(repo, CRATE_NAME, "1.0.0", manifest);

    final var withoutCreds =
        this.mockMvc
            .perform(
                head("/api/v1/crates/{name}/{version}/download", CRATE_NAME, "1.0.0")
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(withoutCreds.getStatus()).isEqualTo(401);

    final var withCreds = this.headDownload(repo, CRATE_NAME, "1.0.0", token);
    assertThat(withCreds.getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("GET config.json returns valid JSON body")
  void getConfigJsonReturnsValidJson() throws Exception {
    final var repo = this.cargoRepo();
    final var token = this.adminProtocolBearerToken();
    final var manifest = "[package]\nname = \"" + CRATE_NAME + "\"\n";
    this.publish(repo, CRATE_NAME, "1.0.0", manifest);

    final var response = this.getConfigJson(repo, token);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentAsString()).contains("dl").contains("api");
  }
}
