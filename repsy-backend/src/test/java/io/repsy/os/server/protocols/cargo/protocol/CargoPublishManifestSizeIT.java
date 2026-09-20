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

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.protocols.cargo.protocol.utils.CrateUtils;
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

/**
 * RPS-1052: {@code cargo publish} reads the crate's {@code Cargo.toml} to decide whether it is a
 * library. A crate whose manifest inflates to gigabytes used to be buffered whole, so a small
 * upload could exhaust the heap of the instance. The manifest is now capped, and an oversized one
 * is answered with a Cargo error and stores nothing.
 */
@DisplayName("Cargo publish caps the size of the crate's Cargo.toml")
class CargoPublishManifestSizeIT extends AbstractIntegrationTest {

  private static final String CRATE = "manifest-crate";
  private static final String PUBLISH = "/{repo}/api/v1/crates/new";
  private static final String INDEX = "/{repo}/ma/ni/" + CRATE;

  private static byte[] publishBody(final long manifestBytes) {
    final var metadata =
        ("{\"name\":\"%s\",\"vers\":\"1.0.0\",\"deps\":[],\"features\":{},\"authors\":[],"
                + "\"description\":\"manifest size fixture\",\"license\":\"MIT\"}")
            .formatted(CRATE)
            .getBytes(StandardCharsets.UTF_8);
    final var crate = crateArchive(manifestBytes);
    final var out = new ByteArrayOutputStream();

    out.writeBytes(
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(metadata.length).array());
    out.writeBytes(metadata);
    out.writeBytes(
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(crate.length).array());
    out.writeBytes(crate);

    return out.toByteArray();
  }

  /** A {@code .crate} whose Cargo.toml is {@code manifestBytes} long and declares a [lib]. */
  private static byte[] crateArchive(final long manifestBytes) {
    final var head = "[package]\nname = \"" + CRATE + "\"\n\n[lib]\n#";
    final var manifest =
        (head + "#".repeat((int) manifestBytes - head.length())).getBytes(StandardCharsets.UTF_8);
    final var bytes = new ByteArrayOutputStream();

    try (final var tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bytes))) {
      final var entry = new TarArchiveEntry(CRATE + "-1.0.0/Cargo.toml");

      entry.setSize(manifest.length);
      tar.putArchiveEntry(entry);
      tar.write(manifest);
      tar.closeArchiveEntry();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    return bytes.toByteArray();
  }

  @Test
  @DisplayName("refuses a Cargo.toml over the limit with a Cargo error and stores nothing")
  void refusesOversizedManifest() throws Exception {
    final var repo = this.seedRepo(RepoType.CARGO, uniqueRepoName("rps1052"));
    final var token = this.adminProtocolBearerToken();

    final var result =
        this.mockMvc
            .perform(
                put(PUBLISH, repo.getName())
                    .header(AUTHORIZATION, token)
                    .content(publishBody(CrateUtils.MAX_CARGO_TOML_BYTES + 1))
                    .with(protocolPort()))
            .andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(400);
    assertThat(result.getResponse().getContentAsString())
        .contains("Cargo.toml in the crate must be at most 10 MiB");

    final var index =
        this.mockMvc
            .perform(get(INDEX, repo.getName()).header(AUTHORIZATION, token).with(protocolPort()))
            .andReturn();

    assertThat(index.getResponse().getStatus()).isEqualTo(404);
  }

  @Test
  @DisplayName("still publishes a Cargo.toml of exactly the limit")
  void publishesManifestAtLimit() throws Exception {
    final var repo = this.seedRepo(RepoType.CARGO, uniqueRepoName("rps1052"));
    final var token = this.adminProtocolBearerToken();

    final var result =
        this.mockMvc
            .perform(
                put(PUBLISH, repo.getName())
                    .header(AUTHORIZATION, token)
                    .content(publishBody(CrateUtils.MAX_CARGO_TOML_BYTES))
                    .with(protocolPort()))
            .andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(200);

    final var index =
        this.mockMvc
            .perform(get(INDEX, repo.getName()).header(AUTHORIZATION, token).with(protocolPort()))
            .andReturn();

    assertThat(index.getResponse().getStatus()).isEqualTo(200);
    assertThat(index.getResponse().getContentAsString()).contains("\"vers\":\"1.0.0\"");
  }
}
