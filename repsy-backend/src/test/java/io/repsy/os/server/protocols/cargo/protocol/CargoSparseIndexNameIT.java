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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * RPS-1212: the sparse index used to serve a crate under its normalized lookup name ({@code
 * my_crate}) rather than the name it was published under ({@code my-crate}), whichever spelling the
 * entry was looked up by. Real {@code cargo} clients cross-check the served entry's {@code name}
 * against the dependency name they resolved and refuse a mismatch, so a hyphenated crate was
 * unusable even though the raw HTTP GET answered 200.
 */
@DisplayName("Cargo sparse index serves a crate under its originally-published name")
class CargoSparseIndexNameIT extends AbstractIntegrationTest {

  private static final String CRATE = "my-crate";
  private static final String PUBLISH = "/{repo}/api/v1/crates/new";
  private static final String INDEX_HYPHENATED = "/{repo}/my/-c/my-crate";
  private static final String INDEX_NORMALIZED = "/{repo}/my/_c/my_crate";
  private static final String DOWNLOAD = "/{repo}/api/v1/crates/my_crate/{vers}/download";

  private static byte[] publishBody(final String name, final String vers) {
    final var metadata =
        ("{\"name\":\"%s\",\"vers\":\"%s\",\"deps\":[],\"features\":{},\"authors\":[],"
                + "\"description\":\"RPS-1212 fixture\",\"license\":\"MIT\"}")
            .formatted(name, vers)
            .getBytes(StandardCharsets.UTF_8);
    final var crate = crateArchive(name, vers);
    final var out = new ByteArrayOutputStream();

    out.writeBytes(
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(metadata.length).array());
    out.writeBytes(metadata);
    out.writeBytes(
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(crate.length).array());
    out.writeBytes(crate);

    return out.toByteArray();
  }

  /** A minimal {@code .crate}: a gzipped tarball holding a manifest and a library root. */
  private static byte[] crateArchive(final String name, final String vers) {
    final var bytes = new ByteArrayOutputStream();

    try (final var tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bytes))) {
      addEntry(tar, name + "-" + vers + "/Cargo.toml", "[package]\nname = \"" + name + "\"\n");
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

  private MvcResult protocolRequest(
      final org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder<?>
          request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn();
  }

  private void publish(final String repoName, final String token, final String vers)
      throws Exception {
    final var result =
        this.protocolRequest(
            put(PUBLISH, repoName).header(AUTHORIZATION, token).content(publishBody(CRATE, vers)));

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
  }

  private String entryNameAt(final String path, final String repoName, final String token)
      throws Exception {
    final var result = this.protocolRequest(get(path, repoName).header(AUTHORIZATION, token));

    assertThat(result.getResponse().getStatus()).isEqualTo(200);

    final var firstLine =
        result.getResponse().getContentAsString().lines().findFirst().orElseThrow();
    return JsonPath.read(firstLine, "$.name");
  }

  @Test
  @DisplayName("reports the original spelling whichever spelling the index is looked up by")
  void servesOriginalNameForBothSpellings() throws Exception {
    final var repo = this.seedRepo(RepoType.CARGO, uniqueRepoName("rps1212"));
    final var token = this.adminProtocolBearerToken();

    this.publish(repo.getName(), token, "1.0.0");

    assertThat(this.entryNameAt(INDEX_HYPHENATED, repo.getName(), token)).isEqualTo(CRATE);
    assertThat(this.entryNameAt(INDEX_NORMALIZED, repo.getName(), token)).isEqualTo(CRATE);
  }

  @Test
  @DisplayName("keeps the crate downloadable at its normalized storage path")
  void crateStillDownloadableAtNormalizedPath() throws Exception {
    final var repo = this.seedRepo(RepoType.CARGO, uniqueRepoName("rps1212"));
    final var token = this.adminProtocolBearerToken();

    this.publish(repo.getName(), token, "1.0.0");

    final var result =
        this.protocolRequest(get(DOWNLOAD, repo.getName(), "1.0.0").header(AUTHORIZATION, token));

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(result.getResponse().getContentType())
        .isEqualTo(MediaType.APPLICATION_OCTET_STREAM_VALUE);
  }

  @Test
  @DisplayName("reports the original spelling for every published version")
  void secondVersionAlsoReportsOriginalName() throws Exception {
    final var repo = this.seedRepo(RepoType.CARGO, uniqueRepoName("rps1212"));
    final var token = this.adminProtocolBearerToken();

    this.publish(repo.getName(), token, "1.0.0");
    this.publish(repo.getName(), token, "2.0.0");

    final var result =
        this.protocolRequest(get(INDEX_HYPHENATED, repo.getName()).header(AUTHORIZATION, token));

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    final var lines = result.getResponse().getContentAsString().lines().toList();
    assertThat(lines).hasSize(2);

    for (final var line : lines) {
      assertThat((String) JsonPath.read(line, "$.name")).isEqualTo(CRATE);
    }
  }

  @Test
  @DisplayName("is unaffected for a crate whose name never needed normalizing")
  void allUnderscoreCrateIsUnaffected() throws Exception {
    final var repo = this.seedRepo(RepoType.CARGO, uniqueRepoName("rps1212"));
    final var token = this.adminProtocolBearerToken();
    final var underscoreCrate = "another_crate";

    final var publishResult =
        this.protocolRequest(
            put(PUBLISH, repo.getName())
                .header(AUTHORIZATION, token)
                .content(publishBody(underscoreCrate, "1.0.0")));
    assertThat(publishResult.getResponse().getStatus()).isEqualTo(200);

    final var result =
        this.protocolRequest(
            get("/{repo}/an/ot/" + underscoreCrate, repo.getName()).header(AUTHORIZATION, token));

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    final var firstLine =
        result.getResponse().getContentAsString().lines().findFirst().orElseThrow();
    assertThat((String) JsonPath.read(firstLine, "$.name")).isEqualTo(underscoreCrate);
  }
}
