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
import io.repsy.os.server.protocols.cargo.shared.crate.repositories.CargoCrateIndexRepository;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * RPS-1721 edge case 3: a second version published under a different spelling of an already
 * published crate ({@code Foo-Bar} then {@code foo_bar}) is refused, matching crates.io.
 *
 * <p>{@code CrateUtils.normalizeCrateName} folds case and {@code -}/{@code _} to look the crate up,
 * exactly as a real {@code cargo} client does when resolving a dependency by name. Before this fix,
 * that same folding let a second publish silently add a version to the crate under a spelling it
 * was never published under: live-probed against a running Repsy instance (a full Spring context
 * over Testcontainers Postgres, the same probe this class now pins), a {@code PUT
 * /{repo}/api/v1/crates/new} of {@code foo_bar} 1.1.0 answered 200 after {@code Foo-Bar} 1.0.0 had
 * already been published, and the version was added to {@code Foo-Bar} without changing its served
 * name. Real crates.io refuses this.
 */
@DisplayName("Cargo publish refuses a different spelling of an existing crate's name (RPS-1721)")
class CargoPublishNameSpellingIT extends AbstractIntegrationTest {

  private static final String PUBLISH = "/{repo}/api/v1/crates/new";

  @Autowired private CargoCrateRepository crateRepository;
  @Autowired private CargoCrateIndexRepository crateIndexRepository;

  private static byte[] publishBody(final String name, final String vers) {
    final var metadata =
        ("{\"name\":\"%s\",\"vers\":\"%s\",\"deps\":[],\"features\":{},\"authors\":[],"
                + "\"description\":\"RPS-1721 fixture\",\"license\":\"MIT\"}")
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

  private ResultActions publish(final String repoName, final String token, final byte[] body)
      throws Exception {
    return this.mockMvc.perform(
        put(PUBLISH, repoName).with(protocolPort()).header(AUTHORIZATION, token).content(body));
  }

  @Test
  @DisplayName(
      "a second version under a different spelling of an existing crate's name is refused, not"
          + " silently folded into it")
  void refusesSecondVersionUnderADifferentSpelling() throws Exception {
    final var repo = this.seedRepo(RepoType.CARGO, uniqueRepoName("rps1721"));
    final var token = this.adminProtocolBearerToken();

    this.publish(repo.getName(), token, publishBody("Foo-Bar", "1.0.0")).andExpect(status().isOk());

    this.publish(repo.getName(), token, publishBody("foo_bar", "1.1.0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].detail").exists());

    this.entityManager.flush();
    this.entityManager.clear();

    final var crate = this.crateRepository.findByRepoIdAndName(repo.getId(), "foo_bar");
    assertThat(crate).isPresent();
    assertThat(crate.orElseThrow().getOriginalName()).isEqualTo("Foo-Bar");
    assertThat(crate.orElseThrow().getMaxVersion())
        .as("the refused publish added no version")
        .isEqualTo("1.0.0");
    assertThat(this.crateIndexRepository.findByCrateIdAndVers(crate.orElseThrow().getId(), "1.1.0"))
        .as("no index row was written for the refused version")
        .isEmpty();
  }

  @Test
  @DisplayName("republishing under the exact same spelling still succeeds")
  void allowsASecondVersionUnderTheSameSpelling() throws Exception {
    final var repo = this.seedRepo(RepoType.CARGO, uniqueRepoName("rps1721same"));
    final var token = this.adminProtocolBearerToken();

    this.publish(repo.getName(), token, publishBody("Foo-Baz", "1.0.0")).andExpect(status().isOk());
    this.publish(repo.getName(), token, publishBody("Foo-Baz", "1.1.0")).andExpect(status().isOk());

    this.entityManager.flush();
    this.entityManager.clear();

    final var crate = this.crateRepository.findByRepoIdAndName(repo.getId(), "foo_baz");
    assertThat(crate).isPresent();
    assertThat(crate.orElseThrow().getMaxVersion()).isEqualTo("1.1.0");
  }
}
