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
package io.repsy.os.server.protocols.shared.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.H2IntegrationTest;
import io.repsy.os.generated.model.RepoListInfo;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The embedded-H2 counterpart of {@code RepoCollectionControllerIT} (RPS-1268): the queries behind
 * {@code GET /api/repos} and {@code GET /api/repos/counts} on H2. Like the other H2 tests it calls
 * the service instead of MockMvc, so that it shares the one application context of the H2 suites.
 */
@DisplayName("Repo collection queries on embedded H2 (RPS-1268)")
class H2RepoCollectionIT extends H2IntegrationTest {

  private static final Instant TIED_AT = Instant.parse("2026-03-04T05:06:07Z");

  @Autowired private RepoTxService repoTxService;
  @Autowired private RepoRepository repoRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @PersistenceContext private EntityManager entityManager;

  private static String tag() {
    return "h2c" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }

  private void insertRepo(final String name, final RepoType type) {
    final var repo = new Repo();
    repo.setName(name);
    repo.setType(type);
    repo.setPrivateRepo(false);
    repo.setAllowOverride(true);
    repo.setDiskUsage(0);

    this.repoRepository.saveAndFlush(repo);
  }

  private List<String> names(final RepoType type, final String query, final Sort sort) {
    return this.repoTxService.listRepos(type, query, PageRequest.of(0, 100, sort)).stream()
        .map(RepoListInfo::getName)
        .toList();
  }

  @Test
  @DisplayName("filters by type and name, and takes % and _ of the name filter literally")
  void filtersAndLiterals() {
    final var tag = tag();
    this.insertRepo(tag + "a_c", RepoType.MAVEN);
    this.insertRepo(tag + "abc", RepoType.MAVEN);
    this.insertRepo(tag + "a-c", RepoType.NPM);
    final var byName = Sort.by("name");

    assertThat(this.names(null, tag, byName)).hasSize(3);
    assertThat(this.names(RepoType.MAVEN, tag, byName)).containsExactly(tag + "a_c", tag + "abc");
    assertThat(this.names(RepoType.NPM, tag.toUpperCase(Locale.ROOT), byName))
        .containsExactly(tag + "a-c");
    assertThat(this.names(null, tag + "a_c", byName)).containsExactly(tag + "a_c");
    assertThat(this.names(null, tag + "a%", byName)).isEmpty();
    assertThat(this.names(null, "%", byName)).isEmpty();
    assertThat(this.names(null, tag + "a\\", byName)).isEmpty();
    assertThat(this.names(null, null, byName)).isNotEmpty();
    assertThat(this.names(null, " ", byName)).isNotEmpty();
  }

  @Test
  @DisplayName("lists repos created in the same instant once each, in the same order on every read")
  void tiedRowsPageStably() {
    final var tag = tag();
    final var expected = new ArrayList<String>();
    for (var i = 0; i < 27; i++) {
      final var name = tag + "-" + i;
      this.insertRepo(name, RepoType.values()[i % 9]);
      expected.add(name);
    }
    this.jdbcTemplate.update(
        "update \"public\".\"repo\" set \"created_at\" = ? where \"name\" like ?",
        Timestamp.from(TIED_AT),
        tag + "-%");
    this.entityManager.clear();

    final var first = this.readAllPages(tag);
    final var second = this.readAllPages(tag);

    assertThat(first).hasSameSizeAs(expected).doesNotHaveDuplicates();
    assertThat(first).containsExactlyInAnyOrderElementsOf(expected);
    assertThat(second).isEqualTo(first);
  }

  private List<String> readAllPages(final String tag) {
    final var names = new ArrayList<String>();
    var page = 0;

    while (true) {
      final var result =
          this.repoTxService.listRepos(
              null, tag, PageRequest.of(page++, 10, Sort.by(Sort.Direction.DESC, "createdAt")));

      result.forEach(repo -> names.add(repo.getName()));
      if (!result.hasNext()) {
        return names;
      }
    }
  }

  @Test
  @DisplayName("counts every type, with 0 for a type without a repo")
  void counts() {
    final var before = this.repoTxService.getRepoCounts();
    final var tag = tag();
    this.insertRepo(tag + "-1", RepoType.GOLANG);
    this.insertRepo(tag + "-2", RepoType.GOLANG);

    final var counts = this.repoTxService.getRepoCounts();

    assertThat(counts).containsOnlyKeys(RepoType.values());
    assertThat(counts.get(RepoType.GOLANG)).isEqualTo(before.get(RepoType.GOLANG) + 2);
    assertThat(counts.get(RepoType.MAVEN)).isEqualTo(before.get(RepoType.MAVEN));

    this.repoRepository.deleteAllInBatch();
    this.entityManager.flush();
    this.entityManager.clear();

    assertThat(this.repoTxService.getRepoCounts().values()).containsOnly(0L);
  }
}
