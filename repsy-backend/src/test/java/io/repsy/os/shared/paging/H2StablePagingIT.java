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
package io.repsy.os.shared.paging;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.H2IntegrationTest;
import io.repsy.os.server.protocols.cargo.shared.crate.entities.CargoCrate;
import io.repsy.os.server.protocols.cargo.shared.crate.repositories.CargoCrateRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.shared.token.entities.RepoDeployToken;
import io.repsy.os.server.shared.token.repositories.RepoDeployTokenRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The H2 counterpart of {@link StablePagingIT} (RPS-1298), where the order of tied rows is the
 * least stable. Like the other H2 tests it calls the repositories instead of MockMvc, so that all
 * of them share one application context; the tie-breaker sits on the repositories, so the pages are
 * the same ones the endpoints serve.
 */
@DisplayName("Paged lists break sort ties by id on embedded H2 (RPS-1298)")
class H2StablePagingIT extends H2IntegrationTest {

  private static final int ROWS = 60;
  private static final int PAGE_SIZE = 5;
  private static final Instant TIED_AT = Instant.parse("2026-03-04T05:06:07Z");

  @Autowired private RepoRepository repoRepository;
  @Autowired private ArtifactRepository artifactRepository;
  @Autowired private CargoCrateRepository cargoCrateRepository;
  @Autowired private RepoDeployTokenRepository deployTokenRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @PersistenceContext private EntityManager entityManager;

  private Repo repo(final RepoType type) {
    final var repo = new Repo();
    repo.setName("h2tie" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
    repo.setType(type);
    repo.setPrivateRepo(false);
    repo.setAllowOverride(true);
    repo.setDiskUsage(0);
    repo.setCreatedAt(Instant.now());

    return this.repoRepository.saveAndFlush(repo);
  }

  /** Reads every page through {@code query} and returns the names in the order they came. */
  private static <T> List<String> readAllPages(
      final Sort sort, final Function<Pageable, Page<T>> query, final Function<T, String> name) {

    final var names = new ArrayList<String>();
    Page<T> page;
    var number = 0;

    do {
      page = query.apply(PageRequest.of(number++, PAGE_SIZE, sort));
      page.forEach(item -> names.add(name.apply(item)));
    } while (page.hasNext());

    return names;
  }

  private static <T> void assertEveryRowOnceAndStable(
      final Sort sort,
      final Function<Pageable, Page<T>> query,
      final Function<T, String> name,
      final List<String> expected) {

    final var first = readAllPages(sort, query, name);
    final var second = readAllPages(sort, query, name);

    assertThat(first).hasSameSizeAs(expected).doesNotHaveDuplicates();
    assertThat(new HashSet<>(first)).isEqualTo(new HashSet<>(expected));
    assertThat(second).isEqualTo(first);
  }

  @Test
  @DisplayName("Maven artifacts updated in the same instant are each listed once")
  void mavenArtifacts() {
    final var repo = this.repo(RepoType.MAVEN);
    final var names = new ArrayList<String>();
    for (var i = 0; i < ROWS; i++) {
      final var artifact = new Artifact();
      artifact.setRepo(repo);
      artifact.setGroupName("com.tie");
      artifact.setArtifactName("art" + i);
      artifact.setName("art" + i);
      artifact.setPackaging("jar");
      artifact.setPlugin(false);
      artifact.setLatest("1.0.0");
      artifact.setRelease("1.0.0");
      artifact.setLastUpdatedAt(TIED_AT);
      this.artifactRepository.save(artifact);
      names.add("art" + i);
    }
    this.entityManager.flush();
    this.entityManager.clear();

    assertEveryRowOnceAndStable(
        Sort.by(Sort.Direction.DESC, "lastUpdatedAt"),
        pageable ->
            this.artifactRepository.findAllByRepoIdAndContainsGroupName(repo.getId(), "", pageable),
        artifact -> artifact.getArtifactName(),
        names);
  }

  @Test
  @DisplayName("Cargo crates with the same download count are each listed once")
  void cargoCrates() {
    final var repo = this.repo(RepoType.CARGO);
    final var names = new ArrayList<String>();
    for (var i = 0; i < ROWS; i++) {
      final var crate = new CargoCrate();
      crate.setRepo(repo);
      crate.setName("crate" + i);
      crate.setOriginalName("crate" + i);
      crate.setMaxVersion("1.0.0");
      crate.setCreatedAt(TIED_AT);
      crate.setLastUpdatedAt(TIED_AT);
      this.cargoCrateRepository.save(crate);
      names.add("crate" + i);
    }
    this.entityManager.flush();
    this.entityManager.clear();

    assertEveryRowOnceAndStable(
        Sort.by(Sort.Direction.DESC, "totalDownloads"),
        pageable ->
            this.cargoCrateRepository.findAllByRepoIdAndNameContaining(repo.getId(), "", pageable),
        crate -> crate.name(),
        names);
  }

  @Test
  @DisplayName("deploy tokens created in the same instant are each listed once")
  void deployTokens() {
    final var repo = this.repo(RepoType.MAVEN);
    final var names = new ArrayList<String>();
    for (var i = 0; i < ROWS; i++) {
      final var token = new RepoDeployToken();
      token.setRepo(repo);
      token.setName("token" + i);
      token.setUsername("user-token" + i);
      token.setToken(UUID.randomUUID().toString().replace("-", ""));
      token.setTokenDurationDay(30);
      this.deployTokenRepository.save(token);
      names.add("token" + i);
    }
    this.entityManager.flush();
    this.jdbcTemplate.update(
        "update \"public\".\"repo_deploy_token\" set \"created_at\" = ? where \"repo_id\" = ?",
        Timestamp.from(TIED_AT),
        repo.getId());
    this.entityManager.clear();

    assertEveryRowOnceAndStable(
        Sort.by(Sort.Direction.DESC, "createdAt"),
        pageable -> this.deployTokenRepository.findAllByRepoId(repo.getId(), pageable),
        token -> token.getName(),
        names);
  }
}
