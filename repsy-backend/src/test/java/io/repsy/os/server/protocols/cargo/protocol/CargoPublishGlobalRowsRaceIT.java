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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.cargo.shared.crate.storage.CargoStorageService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1271: two concurrent publishes that introduce the same new author, keyword or category both
 * succeed.
 *
 * <p>The author, keyword and category tables are global: every repo and crate shares them. A
 * publish used to look a value up and insert it when missing, so two publishes that both missed it
 * both inserted, and the loser failed on the unique index with a 500 (and aborted its PostgreSQL
 * transaction, which also holds the version row and the file write). The insert now skips a value
 * that exists and reads it back.
 *
 * <p>Runs without a test transaction: the race needs both publishes to commit. It uses author,
 * keyword and category strings unique to each test, and deletes exactly the rows of those values
 * and the repos and users it commits. It never empties a shared table.
 *
 * <p>{@link UsageUpdateService} is mocked because usage accounting is not under test.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Cargo publish shares new authors, keywords and categories safely (RPS-1271)")
class CargoPublishGlobalRowsRaceIT extends AbstractIntegrationTest {

  private static final String PUBLISH_PATH = "/{repo}/api/v1/crates/new";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @MockitoBean private UsageUpdateService usageUpdateService;

  /** A spy that calls through, so a test can hold one publish inside its transaction. */
  @MockitoSpyBean private CargoStorageService cargoStorageService;

  @Autowired private RepoTxService repoTxService;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();
  private final List<String> createdAuthors = new ArrayList<>();
  private final List<String> createdKeywords = new ArrayList<>();
  private final List<String> createdCategories = new ArrayList<>();

  @AfterEach
  void deleteCommittedData() {
    // The repos go first: they cascade to the crates and the rows that join a crate to a value.
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.userRepository.deleteAllById(this.createdUserIds);
    this.createdUserIds.clear();
    this.createdAuthors.forEach(
        value -> this.jdbcTemplate.update("delete from cargo_author where author = ?", value));
    this.createdKeywords.forEach(
        value -> this.jdbcTemplate.update("delete from cargo_keyword where keyword = ?", value));
    this.createdCategories.forEach(
        value -> this.jdbcTemplate.update("delete from cargo_category where category = ?", value));
    this.createdAuthors.clear();
    this.createdKeywords.clear();
    this.createdCategories.clear();
  }

  private Repo cargoRepo() {
    final var name = uniqueRepoName("cargo-glob");
    final var created = this.repoTxService.createRepo(name, RepoType.CARGO, false, null);
    this.createdRepoIds.add(created.getId());
    this.cargoStorageService.createRepo(created.getId());

    return this.repoRepository.findByName(name).orElseThrow();
  }

  private String adminToken() {
    final var userInfo =
        this.userTxService.create(
            uniqueUsername("cargo-admin"), UserRole.ADMIN, VALID_PASSWORD_HASH);
    this.createdUserIds.add(userInfo.getId());

    return this.protocolBearerTokenFor(
        this.userRepository.findById(userInfo.getId()).orElseThrow());
  }

  /** A value no other test, and no earlier run, can have inserted, tracked for the clean-up. */
  private String newValue(final List<String> tracked, final String prefix) {
    final var value = prefix + "-" + randomTag();
    tracked.add(value);

    return value;
  }

  private static byte[] crateArchive(final String name, final String version) {
    final var manifest =
        "[package]\nname = \"%s\"\n".formatted(name).getBytes(StandardCharsets.UTF_8);
    final var bytes = new ByteArrayOutputStream();

    try (final var tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bytes))) {
      final var entry = new TarArchiveEntry(name + "-" + version + "/Cargo.toml");

      entry.setSize(manifest.length);
      tar.putArchiveEntry(entry);
      tar.write(manifest);
      tar.closeArchiveEntry();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    return bytes.toByteArray();
  }

  private static byte[] publishBody(
      final String name,
      final String version,
      final List<String> authors,
      final List<String> keywords,
      final List<String> categories) {
    final var crate = crateArchive(name, version);
    final var metadata =
        MAPPER
            .writeValueAsString(
                Map.of(
                    "name",
                    name,
                    "vers",
                    version,
                    "deps",
                    List.of(),
                    "features",
                    Map.of(),
                    "authors",
                    authors,
                    "keywords",
                    keywords,
                    "categories",
                    categories,
                    "description",
                    "global rows race fixture",
                    "license",
                    "MIT"))
            .getBytes(StandardCharsets.UTF_8);
    final var out = new ByteArrayOutputStream();

    out.writeBytes(u32le(metadata.length));
    out.writeBytes(metadata);
    out.writeBytes(u32le(crate.length));
    out.writeBytes(crate);

    return out.toByteArray();
  }

  private static byte[] u32le(final int value) {
    return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
  }

  private MockHttpServletResponse push(
      final Repo repo,
      final String name,
      final List<String> authors,
      final List<String> keywords,
      final List<String> categories,
      final String token)
      throws Exception {
    final var request =
        put(PUBLISH_PATH, repo.getName())
            .header(AUTHORIZATION, token)
            .content(publishBody(name, "1.0.0", authors, keywords, categories));

    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private int rowCount(final String table, final String column, final String value) {
    final var count =
        this.jdbcTemplate.queryForObject(
            "select count(*) from " + table + " where " + column + " = ?", Integer.class, value);

    return count == null ? 0 : count;
  }

  /** The number of crates joined to the value through the given join table and column. */
  private int linkedCrates(
      final String joinTable,
      final String joinColumn,
      final String table,
      final String column,
      final String value) {
    final var count =
        this.jdbcTemplate.queryForObject(
            ("select count(*) from %s j join %s v on v.id = j.%s where v.%s = ?")
                .formatted(joinTable, table, joinColumn, column),
            Integer.class,
            value);

    return count == null ? 0 : count;
  }

  @Test
  @DisplayName("two concurrent publishes of different crates sharing brand-new values both succeed")
  void concurrentPublishesSharingNewValuesBothSucceed() throws Exception {
    final var repo = this.cargoRepo();
    final var token = this.adminToken();
    final var author = this.newValue(this.createdAuthors, "rps1271 Author <a@example.com>");
    final var keyword = this.newValue(this.createdKeywords, "rps1271kw");
    final var category = this.newValue(this.createdCategories, "rps1271cat");
    final var firstName = "glob" + randomTag();
    final var secondName = "glob" + randomTag();

    // Holds the first publish inside the storage write, which is inside its transaction, so its
    // three new rows are uncommitted when the second publish reaches its own inserts of them.
    final var firstWriting = new CountDownLatch(1);
    final var releaseFirst = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              firstWriting.countDown();
              releaseFirst.await(30, TimeUnit.SECONDS);
              return invocation.callRealMethod();
            })
        .doAnswer(InvocationOnMock::callRealMethod)
        .when(this.cargoStorageService)
        .writeCrateAndIndex(any(), any(), any(), any(), any(), any());

    final var executor = Executors.newFixedThreadPool(2);
    try {
      final var first =
          executor.submit(
              () ->
                  this.push(
                      repo,
                      firstName,
                      List.of(author),
                      List.of(keyword),
                      List.of(category),
                      token));
      assertThat(firstWriting.await(30, TimeUnit.SECONDS)).isTrue();

      final var second =
          executor.submit(
              () ->
                  this.push(
                      repo,
                      secondName,
                      List.of(author),
                      List.of(keyword),
                      List.of(category),
                      token));
      // Long enough for the second publish to reach its inserts and wait on the first's rows.
      TimeUnit.MILLISECONDS.sleep(500);
      releaseFirst.countDown();

      final var firstResponse = first.get(30, TimeUnit.SECONDS);
      final var secondResponse = second.get(30, TimeUnit.SECONDS);

      assertThat(firstResponse.getStatus()).as(firstResponse.getContentAsString()).isEqualTo(200);
      assertThat(secondResponse.getStatus())
          .as(
              "a value the first publish introduced is not a conflict (409) or an error (500) for the second")
          .isEqualTo(200);
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
    }

    assertThat(this.rowCount("cargo_author", "author", author)).isEqualTo(1);
    assertThat(this.rowCount("cargo_keyword", "keyword", keyword)).isEqualTo(1);
    assertThat(this.rowCount("cargo_category", "category", category)).isEqualTo(1);
    assertThat(
            this.linkedCrates("cargo_crate_author", "author_id", "cargo_author", "author", author))
        .as("both crates are linked to the one author row")
        .isEqualTo(2);
    assertThat(
            this.linkedCrates(
                "cargo_crate_keyword", "keyword_id", "cargo_keyword", "keyword", keyword))
        .isEqualTo(2);
    assertThat(
            this.linkedCrates(
                "cargo_crate_category", "category_id", "cargo_category", "category", category))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("a publish listing the same new values twice, in any order, stores each once")
  void repeatedValuesInOnePublishAreStoredOnce() throws Exception {
    final var repo = this.cargoRepo();
    final var token = this.adminToken();
    final var authorB = this.newValue(this.createdAuthors, "rps1271 B <b@example.com>");
    final var authorA = this.newValue(this.createdAuthors, "rps1271 A <a@example.com>");
    final var keyword = this.newValue(this.createdKeywords, "rps1271kw");
    final var category = this.newValue(this.createdCategories, "rps1271cat");
    final var name = "glob" + randomTag();

    final var response =
        this.push(
            repo,
            name,
            List.of(authorB, authorA, authorB),
            List.of(keyword, keyword),
            List.of(category, category),
            token);

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    assertThat(this.rowCount("cargo_author", "author", authorA)).isEqualTo(1);
    assertThat(this.rowCount("cargo_author", "author", authorB)).isEqualTo(1);
    assertThat(this.rowCount("cargo_keyword", "keyword", keyword)).isEqualTo(1);
    assertThat(this.rowCount("cargo_category", "category", category)).isEqualTo(1);
    assertThat(
            this.linkedCrates("cargo_crate_author", "author_id", "cargo_author", "author", authorA))
        .isEqualTo(1);
  }
}
