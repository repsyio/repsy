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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * RPS-1605: the sparse index lists a crate's versions in publish order, on every read and after a
 * yank. It had no ORDER BY, so PostgreSQL answered in heap order, and a yank rewrites the row and
 * moves it, which flipped {@code 1.0.0, 2.0.0} to {@code 2.0.0, 1.0.0}.
 */
@DisplayName("Cargo sparse index lists versions in publish order")
class CargoSparseIndexOrderIT extends AbstractIntegrationTest {

  private static final String CRATE = "order_crate";
  private static final String PUBLISH = "/{repo}/api/v1/crates/new";
  private static final String INDEX = "/{repo}/or/de/order_crate";
  private static final String YANK = "/{repo}/api/v1/crates/order_crate/{vers}/yank";
  private static final String UNYANK = "/{repo}/api/v1/crates/order_crate/{vers}/unyank";

  private static byte[] publishBody(final String vers) {
    final var metadata =
        ("{\"name\":\"%s\",\"vers\":\"%s\",\"deps\":[],\"features\":{},\"authors\":[],"
                + "\"description\":\"RPS-1605 fixture\",\"license\":\"MIT\"}")
            .formatted(CRATE, vers)
            .getBytes(StandardCharsets.UTF_8);
    final var crate = crateArchive(vers);
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
  private static byte[] crateArchive(final String vers) {
    final var bytes = new ByteArrayOutputStream();

    try (final var tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bytes))) {
      addEntry(tar, CRATE + "-" + vers + "/Cargo.toml", "[package]\nname = \"" + CRATE + "\"\n");
      addEntry(tar, CRATE + "-" + vers + "/src/lib.rs", "");
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

  private int status(final AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse().getStatus();
  }

  private void publish(final String repoName, final String token, final String vers)
      throws Exception {
    assertThat(
            this.status(
                put(PUBLISH, repoName).header(AUTHORIZATION, token).content(publishBody(vers))))
        .isEqualTo(200);
  }

  private List<String> indexLines(final String repoName, final String token) throws Exception {
    final var response =
        this.mockMvc
            .perform(get(INDEX, repoName).header(AUTHORIZATION, token).with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(200);
    return response.getContentAsString().lines().toList();
  }

  private static List<String> versions(final List<String> lines) {
    return lines.stream().map(line -> (String) JsonPath.read(line, "$.vers")).toList();
  }

  @Test
  @DisplayName("keeps 1.0.0, 2.0.0 on every read, before and after a yank of 1.0.0")
  void publishOrderSurvivesAYank() throws Exception {
    final var repo = this.seedRepo(RepoType.CARGO, uniqueRepoName("rps1605"));
    final var token = this.adminProtocolBearerToken();

    this.publish(repo.getName(), token, "1.0.0");
    this.publish(repo.getName(), token, "2.0.0");
    assertThat(versions(this.indexLines(repo.getName(), token))).containsExactly("1.0.0", "2.0.0");

    assertThat(this.status(delete(YANK, repo.getName(), "1.0.0").header(AUTHORIZATION, token)))
        .isEqualTo(200);

    final var first = this.indexLines(repo.getName(), token);
    final var second = this.indexLines(repo.getName(), token);

    assertThat(versions(first)).containsExactly("1.0.0", "2.0.0");
    assertThat(versions(second)).containsExactly("1.0.0", "2.0.0");
    assertThat((Boolean) JsonPath.read(first.getFirst(), "$.yanked")).isTrue();
    assertThat((Boolean) JsonPath.read(first.getLast(), "$.yanked")).isFalse();

    assertThat(this.status(put(UNYANK, repo.getName(), "1.0.0").header(AUTHORIZATION, token)))
        .isEqualTo(200);

    assertThat(versions(this.indexLines(repo.getName(), token))).containsExactly("1.0.0", "2.0.0");
  }

  @Test
  @DisplayName("orders by the publish time of the row, not by its id, and a yank leaves it alone")
  void ordersByCreatedAtAndAYankKeepsIt() throws Exception {
    final var repo = this.seedRepo(RepoType.CARGO, uniqueRepoName("rps1605"));
    final var token = this.adminProtocolBearerToken();

    this.publish(repo.getName(), token, "1.0.0");
    this.publish(repo.getName(), token, "2.0.0");
    this.publish(repo.getName(), token, "3.0.0");

    // 1.0.0 becomes the newest row: the index must follow the timestamp, not the id or the heap.
    final var latest =
        Timestamp.from(Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.SECONDS));
    this.jdbcTemplate.update(
        "update \"cargo_crate_index\" set \"created_at\" = ? where \"vers\" = '1.0.0'"
            + " and \"crate_id\" in (select \"c\".\"id\" from \"cargo_crate\" \"c\""
            + " where \"c\".\"repo_id\" = ?)",
        latest,
        repo.getId());

    assertThat(versions(this.indexLines(repo.getName(), token)))
        .containsExactly("2.0.0", "3.0.0", "1.0.0");

    assertThat(this.status(delete(YANK, repo.getName(), "1.0.0").header(AUTHORIZATION, token)))
        .isEqualTo(200);

    assertThat(versions(this.indexLines(repo.getName(), token)))
        .containsExactly("2.0.0", "3.0.0", "1.0.0");
    assertThat(
            this.jdbcTemplate.queryForObject(
                "select \"i\".\"created_at\" from \"cargo_crate_index\" \"i\""
                    + " join \"cargo_crate\" \"c\" on \"c\".\"id\" = \"i\".\"crate_id\""
                    + " where \"c\".\"repo_id\" = ? and \"i\".\"vers\" = '1.0.0'",
                Timestamp.class,
                repo.getId()))
        .isEqualTo(latest);
  }
}
