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
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.cargo.shared.crate.entities.CargoCrate;
import io.repsy.os.server.protocols.cargo.shared.crate.repositories.CargoCrateRepository;
import io.repsy.os.server.protocols.helm.shared.chart.entities.HelmChart;
import io.repsy.os.server.protocols.helm.shared.chart.entities.HelmChartVersion;
import io.repsy.os.server.protocols.helm.shared.chart.repositories.HelmChartRepository;
import io.repsy.os.server.protocols.helm.shared.chart.repositories.HelmChartVersionRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.shared.token.entities.RepoDeployToken;
import io.repsy.os.server.shared.token.repositories.RepoDeployTokenRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Rows that tie on the requested sort come back in one stable order on every page (RPS-1298).
 *
 * <p>Each test creates more rows than a page holds, gives every one the same value in the column
 * the list is sorted by, and reads all pages twice through the real endpoint. A row missing from
 * the pages, listed twice, or listed in a different place the second time means the query's order
 * left the tied rows to the database.
 */
@DisplayName("Paged lists break sort ties by id (RPS-1298)")
class StablePagingIT extends AbstractIntegrationTest {

  private static final int ROWS = 60;
  private static final int PAGE_SIZE = 5;
  private static final Instant TIED_AT = Instant.parse("2026-03-04T05:06:07Z");

  @Autowired private ArtifactRepository artifactRepository;
  @Autowired private CargoCrateRepository cargoCrateRepository;
  @Autowired private HelmChartRepository helmChartRepository;
  @Autowired private HelmChartVersionRepository helmChartVersionRepository;
  @Autowired private RepoDeployTokenRepository deployTokenRepository;

  /**
   * Reads every page of {@code request} and returns the values of {@code field}, page after page.
   */
  private List<String> readAllPages(
      final String path, final String bearer, final String sort, final String field)
      throws Exception {

    final var values = new ArrayList<String>();
    var totalPages = 1;

    for (var page = 0; page < totalPages; page++) {
      final MockHttpServletRequestBuilder request =
          get(path)
              .header(AUTHORIZATION, bearer)
              .param("page", String.valueOf(page))
              .param("size", String.valueOf(PAGE_SIZE))
              .param("sort", sort);
      final var body =
          this.perform(request)
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();

      totalPages = JsonPath.<Integer>read(body, "$.data.page.totalPages");
      values.addAll(JsonPath.<List<String>>read(body, "$.data.content[*]." + field));
    }

    return values;
  }

  private void assertEveryRowOnceAndStable(
      final String path,
      final String bearer,
      final String sort,
      final String field,
      final List<String> expected)
      throws Exception {

    final var first = this.readAllPages(path, bearer, sort, field);
    final var second = this.readAllPages(path, bearer, sort, field);

    assertThat(first).hasSameSizeAs(expected).doesNotHaveDuplicates();
    assertThat(new HashSet<>(first)).isEqualTo(new HashSet<>(expected));
    assertThat(second).isEqualTo(first);
  }

  @Test
  @DisplayName("users created in the same instant are each listed once")
  void users() throws Exception {
    final var admin = this.createUser(uniqueUsername("adm"), UserRole.ADMIN);
    final var prefix = "tie" + randomTag();
    final var names =
        IntStream.range(0, ROWS)
            .mapToObj(i -> prefix + "u" + i)
            .peek(name -> this.createUser(name, UserRole.USER))
            .toList();
    this.entityManager.flush();
    this.jdbcTemplate.update(
        "update users set created_at = ? where username like ?",
        Timestamp.from(TIED_AT),
        prefix + "%");
    this.entityManager.clear();

    final var bearer = this.bearerTokenFor(admin);
    final var first = this.readUsers(bearer, prefix);
    final var second = this.readUsers(bearer, prefix);

    assertThat(first).hasSameSizeAs(names).doesNotHaveDuplicates();
    assertThat(new HashSet<>(first)).isEqualTo(new HashSet<>(names));
    assertThat(second).isEqualTo(first);
  }

  private List<String> readUsers(final String bearer, final String search) throws Exception {
    final var values = new ArrayList<String>();
    var totalPages = 1;

    for (var page = 0; page < totalPages; page++) {
      final var body =
          this.perform(
                  get("/api/users")
                      .header(AUTHORIZATION, bearer)
                      .param("q", search)
                      .param("page", String.valueOf(page))
                      .param("size", String.valueOf(PAGE_SIZE)))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();

      totalPages = JsonPath.<Integer>read(body, "$.data.page.totalPages");
      values.addAll(JsonPath.<List<String>>read(body, "$.data.content[*].username"));
    }

    return values;
  }

  @Test
  @DisplayName("Maven artifacts updated in the same instant are each listed once")
  void mavenArtifacts() throws Exception {
    final var repo = this.seedRepo(RepoType.MAVEN, uniqueRepoName("mvn"));
    final var names = new ArrayList<String>();
    for (var i = 0; i < ROWS; i++) {
      final var artifact = new Artifact();
      artifact.setRepo(this.repoRepository.getReferenceById(repo.getId()));
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

    this.assertEveryRowOnceAndStable(
        "/api/mvn/artifacts/" + repo.getName(),
        this.adminBearerToken(),
        "lastUpdatedAt,desc",
        "artifactName",
        names);
  }

  @Test
  @DisplayName("Cargo crates with the same download count are each listed once")
  void cargoCrates() throws Exception {
    final var repo = this.seedRepo(RepoType.CARGO, uniqueRepoName("crg"));
    final var names = new ArrayList<String>();
    for (var i = 0; i < ROWS; i++) {
      final var crate = new CargoCrate();
      crate.setRepo(this.repoRepository.getReferenceById(repo.getId()));
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

    this.assertEveryRowOnceAndStable(
        "/api/cargo/crates/" + repo.getName(),
        this.adminBearerToken(),
        "downloads,desc",
        "name",
        names);
  }

  @Test
  @DisplayName("Helm charts updated in the same instant are each listed once")
  void helmCharts() throws Exception {
    final var repo = this.seedRepo(RepoType.HELM, uniqueRepoName("helm"));
    final var names = new ArrayList<String>();
    for (var i = 0; i < ROWS; i++) {
      final var chart = new HelmChart();
      chart.setRepo(this.repoRepository.getReferenceById(repo.getId()));
      chart.setName("chart" + i);
      final var savedChart = this.helmChartRepository.save(chart);

      final var version = new HelmChartVersion();
      version.setChart(savedChart);
      version.setVersion("1.0.0");
      version.setDigest("sha256:" + UUID.randomUUID().toString().replace("-", ""));
      version.setSize(1);
      this.helmChartVersionRepository.save(version);
      names.add("chart" + i);
    }
    this.entityManager.flush();
    this.jdbcTemplate.update(
        "update helm_chart_version set created_at = ?, last_updated_at = ?"
            + " where chart_id in (select id from helm_chart where repo_id = ?)",
        Timestamp.from(TIED_AT),
        Timestamp.from(TIED_AT),
        repo.getId());
    this.entityManager.clear();

    this.assertEveryRowOnceAndStable(
        "/api/helm/charts/" + repo.getName(),
        this.adminBearerToken(),
        "updatedAt,desc",
        "name",
        names);
  }

  @Test
  @DisplayName("deploy tokens created in the same instant are each listed once")
  void deployTokens() throws Exception {
    final var repo = this.seedRepo(RepoType.MAVEN, uniqueRepoName("tok"));
    final var names = new ArrayList<String>();
    for (var i = 0; i < ROWS; i++) {
      names.add(this.seedToken(repo, "token" + i).getName());
    }
    this.entityManager.flush();
    this.jdbcTemplate.update(
        "update repo_deploy_token set created_at = ? where repo_id = ?",
        Timestamp.from(TIED_AT),
        repo.getId());
    this.entityManager.clear();

    this.assertEveryRowOnceAndStable(
        "/api/repos/" + repo.getName() + "/deploy-tokens",
        this.adminBearerToken(),
        "createdAt,desc",
        "name",
        names);
  }

  private RepoDeployToken seedToken(final Repo repo, final String name) {
    final var token = new RepoDeployToken();
    token.setRepo(this.repoRepository.getReferenceById(repo.getId()));
    token.setName(name);
    token.setUsername("user-" + name);
    token.setToken(UUID.randomUUID().toString().replace("-", ""));
    token.setTokenDurationDay(30);

    return this.deployTokenRepository.save(token);
  }
}
