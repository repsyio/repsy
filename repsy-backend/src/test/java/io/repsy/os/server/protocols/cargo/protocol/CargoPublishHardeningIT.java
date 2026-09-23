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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.cargo.shared.crate.repositories.CargoCrateMetaRepository;
import io.repsy.os.server.protocols.cargo.shared.crate.repositories.CargoCrateRepository;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/**
 * RPS-1119 and RPS-1141 parts 1 and 2: a {@code cargo publish} body is spooled to disk through a
 * size limit ({@code repsy.cargo.max-crate-size}), a genuinely oversized crate is refused with a
 * Cargo-shaped 413 instead of being read into memory, a deliberate client error (an already
 * published version) keeps answering Cargo's {@code {"errors":[...]}} body with a real message, and
 * the manifest's {@code edition} is persisted to {@code cargo_crate_meta.edition}. The limit is set
 * low here so the crate that exceeds it stays small.
 */
@TestPropertySource(properties = "repsy.cargo.max-crate-size=4KB")
@DisplayName("Cargo publish hardening (RPS-1119, RPS-1141)")
class CargoPublishHardeningIT extends AbstractIntegrationTest {

  private static final String PUBLISH_PATH = "/{repo}/api/v1/crates/new";

  @Autowired private CargoCrateRepository crateRepository;
  @Autowired private CargoCrateMetaRepository crateMetaRepository;

  private static byte[] u32le(final int value) {
    return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
  }

  /** A well-formed publish body: JSON metadata, then a real gzipped-tar {@code .crate}. */
  private static byte[] publishBody(final String name, final String vers, final String manifest) {
    final var metadata =
        ("{\"name\":\"%s\",\"vers\":\"%s\",\"deps\":[],\"features\":{},\"authors\":[],"
                + "\"description\":\"RPS-1119/1141 fixture\",\"license\":\"MIT\"}")
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

  /**
   * A body whose declared crate length is {@code declaredCrateLength}, backed by that many junk
   * bytes. The length is checked against the configured limit before anything is parsed as a tar or
   * gzip stream, so the filler content never needs to be valid.
   */
  private static byte[] publishBodyWithOversizedCrate(
      final String name, final String vers, final int declaredCrateLength) {
    final var metadata =
        ("{\"name\":\"%s\",\"vers\":\"%s\",\"deps\":[],\"features\":{},\"authors\":[],"
                + "\"description\":\"RPS-1119 oversized fixture\",\"license\":\"MIT\"}")
            .formatted(name, vers)
            .getBytes(StandardCharsets.UTF_8);
    final var out = new ByteArrayOutputStream();

    out.writeBytes(u32le(metadata.length));
    out.writeBytes(metadata);
    out.writeBytes(u32le(declaredCrateLength));
    out.writeBytes(new byte[declaredCrateLength]);

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

  private ResultActions publish(final String repoName, final byte[] body) throws Exception {
    return this.mockMvc.perform(
        put(PUBLISH_PATH, repoName)
            .with(protocolPort())
            .header(AUTHORIZATION, this.adminProtocolBearerToken())
            .content(body));
  }

  @Test
  @DisplayName(
      "a crate over the configured limit is refused with a cargo-shaped 413, nothing stored")
  void refusesOversizedCrate() throws Exception {
    final var repo = this.seedRepo(RepoType.CARGO, uniqueRepoName("rps1119"));

    this.publish(repo.getName(), publishBodyWithOversizedCrate("oversized", "1.0.0", 5 * 1024))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.errors[0].detail").exists());

    assertThat(this.crateRepository.findByRepoIdAndName(repo.getId(), "oversized")).isEmpty();
  }

  @Test
  @DisplayName(
      "republishing an existing crate/version answers cargo's error shape with a real message")
  void refusesAlreadyPublishedVersionWithCargoShapedError() throws Exception {
    final var repo = this.seedRepo(RepoType.CARGO, uniqueRepoName("rps1141"));
    final var manifest = "[package]\nname = \"dup-crate\"\n";

    this.publish(repo.getName(), publishBody("dup-crate", "1.0.0", manifest))
        .andExpect(status().isOk());

    this.publish(repo.getName(), publishBody("dup-crate", "1.0.0", manifest))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.errors[0].detail")
                .value("crate `dup-crate@1.0.0` already exists in this registry"));
  }

  @Test
  @DisplayName("persists the edition declared in the crate's Cargo.toml (RPS-1141)")
  void persistsDeclaredEdition() throws Exception {
    final var repo = this.seedRepo(RepoType.CARGO, uniqueRepoName("rps1141edition"));
    final var manifest = "[package]\nname = \"edition-crate\"\nedition = \"2021\"\n";

    this.publish(repo.getName(), publishBody("edition-crate", "1.0.0", manifest))
        .andExpect(status().isOk());
    this.entityManager.flush();
    this.entityManager.clear();

    final var crate =
        this.crateRepository.findByRepoIdAndName(repo.getId(), "edition_crate").orElseThrow();
    final var meta = this.crateMetaRepository.findByCrateIdAndVersion(crate.getId(), "1.0.0");

    assertThat(meta).isPresent();
    assertThat(meta.orElseThrow().getEdition()).isEqualTo("2021");
  }

  @Test
  @DisplayName("leaves edition null when the manifest declares none")
  void leavesEditionNullWhenAbsent() throws Exception {
    final var repo = this.seedRepo(RepoType.CARGO, uniqueRepoName("rps1141noedition"));
    final var manifest = "[package]\nname = \"no-edition-crate\"\n";

    this.publish(repo.getName(), publishBody("no-edition-crate", "1.0.0", manifest))
        .andExpect(status().isOk());
    this.entityManager.flush();
    this.entityManager.clear();

    final var crate =
        this.crateRepository.findByRepoIdAndName(repo.getId(), "no_edition_crate").orElseThrow();
    final var meta = this.crateMetaRepository.findByCrateIdAndVersion(crate.getId(), "1.0.0");

    assertThat(meta).isPresent();
    assertThat(meta.orElseThrow().getEdition()).isNull();
  }
}
