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
import io.repsy.os.AbstractIT;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * RPS-1571: {@code GET /api/v1/crates} (what {@code cargo search} calls) pages in one stable order,
 * answers {@code per_page=0} with no crates and the true total (cargo prints "... and N crates
 * more" from it), and answers a non-numeric {@code per_page} or {@code page} with a cargo error
 * body instead of a raw Java message.
 *
 * <p>The crates are published in reverse name order, so a query without an ORDER BY (PostgreSQL
 * answers in heap order, which is publish order here) fails the order assertions.
 */
@DisplayName("Cargo search pages in a stable order")
class CargoSearchPagingIT extends AbstractIT {

  private static final String PUBLISH = "/{repo}/api/v1/crates/new";
  private static final String SEARCH = "/{repo}/api/v1/crates";

  /** Seven crates with one prefix: three pages of three, and ties on any shorter comparison. */
  private static final List<String> CRATES =
      List.of("srchpg", "srchpg1", "srchpg2", "srchpg_a", "srchpg_b", "srchpga", "srchpgb");

  private static byte[] publishBody(final String crate) {
    final var metadata =
        ("{\"name\":\"%s\",\"vers\":\"1.0.0\",\"deps\":[],\"features\":{},\"authors\":[],"
                + "\"description\":\"RPS-1571 fixture\",\"license\":\"MIT\"}")
            .formatted(crate)
            .getBytes(StandardCharsets.UTF_8);
    final var archive = crateArchive(crate);
    final var out = new ByteArrayOutputStream();

    out.writeBytes(
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(metadata.length).array());
    out.writeBytes(metadata);
    out.writeBytes(
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(archive.length).array());
    out.writeBytes(archive);

    return out.toByteArray();
  }

  /** A minimal {@code .crate}: a gzipped tarball holding a manifest and a library root. */
  private static byte[] crateArchive(final String crate) {
    final var bytes = new ByteArrayOutputStream();

    try (final var tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bytes))) {
      addEntry(tar, crate + "-1.0.0/Cargo.toml", "[package]\nname = \"" + crate + "\"\n");
      addEntry(tar, crate + "-1.0.0/src/lib.rs", "");
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

  /**
   * The crate names of the repo in the order of the database's own {@code ORDER BY name}. Its
   * collation is not Java's (PostgreSQL en_US and H2 order {@code _} and digits differently), and
   * the search is meant to follow it, so this is the oracle rather than a Java sort.
   */
  private List<String> expectedOrder(final String repoName) {
    return this.jdbcTemplate.queryForList(
        "select c.name from cargo_crate c join repo r on r.id = c.repo_id"
            + " where r.name = ? order by c.name",
        String.class,
        repoName);
  }

  /** Publishes {@link #CRATES} in reverse, and returns the repo name and the token. */
  private String[] seedCrates() throws Exception {
    final var repo = this.seedRepo(RepoType.CARGO, uniqueRepoName("rps1571"));
    final var token = this.adminProtocolBearerToken();

    for (final var crate : CRATES.reversed()) {
      assertThat(
              this.status(
                  put(PUBLISH, repo.getName())
                      .header(AUTHORIZATION, token)
                      .content(publishBody(crate))))
          .isEqualTo(200);
    }

    return new String[] {repo.getName(), token};
  }

  private String search(final String[] repo, final String params, final int expectedStatus)
      throws Exception {
    final var response =
        this.mockMvc
            .perform(
                get(SEARCH + params, repo[0]).header(AUTHORIZATION, repo[1]).with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(expectedStatus);
    return response.getContentAsString();
  }

  private static List<String> names(final String body) {
    return JsonPath.read(body, "$.crates[*].name");
  }

  @Test
  @DisplayName("keeps one name order across pages and repeated reads, equal prefixes included")
  void pagesInNameOrder() throws Exception {
    final var repo = this.seedCrates();
    final var seen = new ArrayList<String>();

    for (var page = 1; page <= 3; page++) {
      final var body = this.search(repo, "?q=srchpg&per_page=3&page=" + page, 200);

      assertThat((Integer) JsonPath.read(body, "$.meta.total")).isEqualTo(CRATES.size());
      seen.addAll(names(body));
    }

    // Each page is the next slice of the same order: no crate twice, none skipped.
    final var expected = this.expectedOrder(repo[0]);

    assertThat(expected).containsExactlyInAnyOrderElementsOf(CRATES);
    assertThat(seen).containsExactlyElementsOf(expected);
    // A second read of every page answers the same.
    assertThat(names(this.search(repo, "?q=srchpg&per_page=3&page=2", 200)))
        .containsExactlyElementsOf(expected.subList(3, 6));
    // Past the last page: nothing, and the total is still the whole match.
    final var beyond = this.search(repo, "?q=srchpg&per_page=3&page=4", 200);

    assertThat(names(beyond)).isEmpty();
    assertThat((Integer) JsonPath.read(beyond, "$.meta.total")).isEqualTo(CRATES.size());
  }

  @Test
  @DisplayName("answers per_page=0 with no crates and the true total, not one crate")
  void perPageZeroKeepsTheTotal() throws Exception {
    final var repo = this.seedCrates();
    final var body = this.search(repo, "?q=srchpg&per_page=0", 200);

    assertThat(names(body)).isEmpty();
    assertThat((Integer) JsonPath.read(body, "$.meta.total")).isEqualTo(CRATES.size());
  }

  @Test
  @DisplayName("clamps per_page above 100 to 100 and below 1 to 1")
  void clampsPerPage() throws Exception {
    final var repo = this.seedCrates();

    assertThat(names(this.search(repo, "?q=srchpg&per_page=500", 200)))
        .containsExactlyElementsOf(this.expectedOrder(repo[0]));
    assertThat(names(this.search(repo, "?q=srchpg&per_page=-4", 200))).hasSize(1);
  }

  @Test
  @DisplayName("answers a non-numeric per_page or page with a cargo error body, not a Java message")
  void nonNumericParametersAreCargoErrors() throws Exception {
    final var repo = this.seedCrates();

    for (final var params :
        List.of("?per_page=abc", "?page=xyz", "?per_page=1.5", "?per_page=99999999999")) {
      final var body = this.search(repo, params, 400);
      final var detail = (String) JsonPath.read(body, "$.errors[0].detail");

      assertThat((List<?>) JsonPath.read(body, "$.errors")).hasSize(1);
      assertThat(detail).endsWith("must be a whole number");
      assertThat(body).doesNotContain("NumberFormatException", "For input string");
    }
  }
}
