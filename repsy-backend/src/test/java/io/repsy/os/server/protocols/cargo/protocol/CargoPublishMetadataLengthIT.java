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
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.cargo.protocol.utils.CrateUtils;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1072: {@code cargo publish} metadata longer than its {@code cargo_crate}, {@code
 * cargo_crate_index}, {@code cargo_crate_meta}, {@code cargo_author} or {@code cargo_category}
 * column used to fail the row insert after the {@code .crate} and its index line were written, and
 * the client was answered with the raw JDBC message. The metadata is now held to its column before
 * anything is written: the version, rust-version and links are rejected with a Cargo error that
 * names the field, and the descriptive metadata (URLs, license, authors, categories) is dropped
 * while the crate is published as sent.
 *
 * <p>None of these reaches the database with an over-long value, so they run in this class's test
 * transaction.
 */
@DisplayName("Cargo publish holds metadata to its columns (RPS-1072)")
class CargoPublishMetadataLengthIT extends AbstractIntegrationTest {

  private static final String CRATE = "meta-crate";
  private static final String NORMALIZED = "meta_crate";
  private static final String PUBLISH = "/{repo}/api/v1/crates/new";
  private static final String INDEX = "/{repo}/ma/ni/" + CRATE;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static byte[] crateArchive() {
    final var manifest =
        "[package]\nname = \"%s\"\n\n[lib]\n".formatted(CRATE).getBytes(StandardCharsets.UTF_8);
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

  /** The metadata of a small crate, with the given fields replaced. */
  private static Map<String, Object> metadata(final Map<String, Object> overrides) {
    final var metadata = new LinkedHashMap<String, Object>();

    metadata.put("name", CRATE);
    metadata.put("vers", "1.0.0");
    metadata.put("deps", List.of());
    metadata.put("features", Map.of());
    metadata.put("authors", List.of("Alice"));
    metadata.put("description", "metadata length fixture");
    metadata.put("license", "MIT");
    metadata.putAll(overrides);

    return metadata;
  }

  private static byte[] publishBody(final Map<String, Object> metadata, final byte[] crate) {
    final var json = MAPPER.writeValueAsString(metadata).getBytes(StandardCharsets.UTF_8);
    final var out = new ByteArrayOutputStream();

    out.writeBytes(
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(json.length).array());
    out.writeBytes(json);
    out.writeBytes(
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(crate.length).array());
    out.writeBytes(crate);

    return out.toByteArray();
  }

  private Repo cargoRepo() {
    return this.seedRepo(RepoType.CARGO, uniqueRepoName("cargo"));
  }

  private MockHttpServletResponse publish(
      final Repo repo, final Map<String, Object> metadata, final byte[] crate) throws Exception {
    return this.mockMvc
        .perform(
            put(PUBLISH, repo.getName())
                .header(AUTHORIZATION, this.adminProtocolBearerToken())
                .content(publishBody(metadata, crate))
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private int indexStatus(final Repo repo) throws Exception {
    return this.mockMvc
        .perform(
            get(INDEX, repo.getName())
                .header(AUTHORIZATION, this.adminProtocolBearerToken())
                .with(protocolPort()))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private Map<String, Object> row(final String sql, final Repo repo) {
    this.entityManager.flush();

    return this.jdbcTemplate.queryForMap(sql, repo.getId());
  }

  private Map<String, Object> crateRow(final Repo repo) {
    return this.row(
        "select name, original_name, max_version, homepage, repository from cargo_crate where repo_id = ?",
        repo);
  }

  private Map<String, Object> indexRow(final Repo repo) {
    return this.row(
        """
        select i.vers, i.rust_version, i.links
          from cargo_crate_index i join cargo_crate c on c.id = i.crate_id
          where c.repo_id = ?
        """,
        repo);
  }

  private Map<String, Object> metaRow(final Repo repo) {
    return this.row(
        """
        select m.version, m.rust_version, m.license, m.license_file, m.documentation
          from cargo_crate_meta m join cargo_crate c on c.id = m.crate_id
          where c.repo_id = ?
        """,
        repo);
  }

  private List<String> authors(final Repo repo) {
    this.entityManager.flush();

    return this.jdbcTemplate.queryForList(
        """
        select a.author from cargo_author a
          join cargo_crate_author ca on ca.author_id = a.id
          join cargo_crate c on c.id = ca.crate_id
          where c.repo_id = ?
          order by a.author
        """,
        String.class,
        repo.getId());
  }

  private List<String> categories(final Repo repo) {
    this.entityManager.flush();

    return this.jdbcTemplate.queryForList(
        """
        select a.category from cargo_category a
          join cargo_crate_category ca on ca.category_id = a.id
          join cargo_crate c on c.id = ca.crate_id
          where c.repo_id = ?
          order by a.category
        """,
        String.class,
        repo.getId());
  }

  private int crateRows(final Repo repo) {
    this.entityManager.flush();
    final var count =
        this.jdbcTemplate.queryForObject(
            "select count(*) from cargo_crate where repo_id = ?", Integer.class, repo.getId());

    return count == null ? 0 : count;
  }

  private List<String> storedFiles(final Repo repo) throws IOException {
    final var dir = storageDirOf(repo);
    if (!Files.exists(dir)) {
      return List.of();
    }
    try (final var files = Files.walk(dir)) {
      return files
          .filter(Files::isRegularFile)
          .map(file -> dir.relativize(file).toString())
          .sorted()
          .toList();
    }
  }

  private static String of(final int length) {
    return "x".repeat(length);
  }

  @Test
  @DisplayName("stores every field that fits its column, including a value exactly at the limit")
  void storesValuesAtTheLimit() throws Exception {
    final var repo = this.cargoRepo();
    final var version = "1.0.0-" + of(CrateUtils.MAX_VERSION_LENGTH - "1.0.0-".length());
    final var metadata =
        metadata(
            Map.of(
                "vers", version,
                "rust_version", of(CrateUtils.MAX_RUST_VERSION_LENGTH),
                "links", of(CrateUtils.MAX_LINKS_LENGTH),
                "homepage", of(CrateUtils.MAX_HOMEPAGE_LENGTH),
                "repository", of(CrateUtils.MAX_REPOSITORY_LENGTH),
                "documentation", of(CrateUtils.MAX_DOCUMENTATION_LENGTH),
                "license", of(CrateUtils.MAX_LICENSE_LENGTH),
                "license_file", of(CrateUtils.MAX_LICENSE_FILE_LENGTH),
                "authors", List.of(of(CrateUtils.MAX_AUTHOR_LENGTH)),
                "categories", List.of(of(CrateUtils.MAX_CATEGORY_LENGTH))));

    final var response = this.publish(repo, metadata, crateArchive());

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    assertThat(this.crateRow(repo))
        .containsEntry("max_version", version)
        .containsEntry("homepage", metadata.get("homepage"))
        .containsEntry("repository", metadata.get("repository"));
    assertThat(this.indexRow(repo))
        .containsEntry("vers", version)
        .containsEntry("rust_version", metadata.get("rust_version"))
        .containsEntry("links", metadata.get("links"));
    assertThat(this.metaRow(repo))
        .containsEntry("version", version)
        .containsEntry("rust_version", metadata.get("rust_version"))
        .containsEntry("license", metadata.get("license"))
        .containsEntry("license_file", metadata.get("license_file"))
        .containsEntry("documentation", metadata.get("documentation"));
    assertThat(this.authors(repo)).containsExactly(of(CrateUtils.MAX_AUTHOR_LENGTH));
    assertThat(this.categories(repo)).containsExactly(of(CrateUtils.MAX_CATEGORY_LENGTH));
    assertThat(this.indexStatus(repo)).isEqualTo(200);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          vers         | version must be at most 64 characters
          rust_version | rust-version must be at most 20 characters
          links        | links must be at most 255 characters
          """)
  @DisplayName("rejects a field one character over its column with a Cargo error that names it")
  void rejectsOverLongField(final String field, final String detail) throws Exception {
    final var repo = this.cargoRepo();
    final var overLong =
        switch (field) {
          case "vers" -> "1.0.0-" + of(CrateUtils.MAX_VERSION_LENGTH - "1.0.0-".length() + 1);
          case "rust_version" -> of(CrateUtils.MAX_RUST_VERSION_LENGTH + 1);
          default -> of(CrateUtils.MAX_LINKS_LENGTH + 1);
        };

    final var response = this.publish(repo, metadata(Map.of(field, overLong)), crateArchive());

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString())
        .isEqualTo("{\"errors\":[{\"detail\":\"%s\"}]}".formatted(detail));
    assertThat(this.crateRows(repo)).as("no crate row").isZero();
    assertThat(this.storedFiles(repo)).as("no .crate file or index line").isEmpty();
    assertThat(this.indexStatus(repo)).as("not in the sparse index").isEqualTo(404);
  }

  @Test
  @DisplayName("drops an over-long URL, license and documentation, keeps the crate as sent")
  void dropsOverLongDescriptiveValues() throws Exception {
    final var repo = this.cargoRepo();
    final var crate = crateArchive();
    final var metadata =
        metadata(
            Map.of(
                "homepage", "https://example.test/" + of(CrateUtils.MAX_HOMEPAGE_LENGTH),
                "repository", "https://example.test/" + of(CrateUtils.MAX_REPOSITORY_LENGTH),
                "documentation", "https://example.test/" + of(CrateUtils.MAX_DOCUMENTATION_LENGTH),
                "license", "MIT OR " + of(CrateUtils.MAX_LICENSE_LENGTH),
                "license_file", of(CrateUtils.MAX_LICENSE_FILE_LENGTH + 1)));

    final var response = this.publish(repo, metadata, crate);

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    assertThat(this.crateRow(repo))
        .containsEntry("max_version", "1.0.0")
        .containsEntry("homepage", null)
        .containsEntry("repository", null);
    assertThat(this.metaRow(repo))
        .containsEntry("license", null)
        .containsEntry("license_file", null)
        .containsEntry("documentation", null);
    assertThat(this.indexStatus(repo)).isEqualTo(200);
    assertThat(
            storageDirOf(repo)
                .resolve("crates")
                .resolve(NORMALIZED)
                .resolve(NORMALIZED + "-1.0.0.crate"))
        .hasBinaryContent(crate);
  }

  @Test
  @DisplayName("drops only the authors and categories over their column and keeps the others")
  void dropsOverLongAuthorsAndCategories() throws Exception {
    final var repo = this.cargoRepo();
    final var metadata =
        metadata(
            Map.of(
                "authors", List.of("Alice", of(CrateUtils.MAX_AUTHOR_LENGTH + 1), "Bob"),
                "categories",
                    List.of(of(CrateUtils.MAX_CATEGORY_LENGTH + 1), "development-tools")));

    final var response = this.publish(repo, metadata, crateArchive());

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    assertThat(this.authors(repo)).containsExactly("Alice", "Bob");
    assertThat(this.categories(repo)).containsExactly("development-tools");
  }
}
