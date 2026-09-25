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
package io.repsy.os.server.protocols;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.H2IntegrationTest;
import io.repsy.os.server.protocols.helm.shared.chart.entities.HelmChart;
import io.repsy.os.server.protocols.helm.shared.chart.entities.HelmChartVersion;
import io.repsy.os.server.protocols.helm.shared.chart.repositories.HelmChartRepository;
import io.repsy.os.server.protocols.helm.shared.chart.repositories.HelmChartVersionRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetVersionInfo;
import io.repsy.protocols.nuget.shared.packages.services.NuGetPackageService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The queries of the list endpoints that RPS-1304 and RPS-1307 changed, run on embedded H2: their
 * SQL has to work on both databases.
 */
@DisplayName("List queries on embedded H2")
class H2ListQueriesIT extends H2IntegrationTest {

  @Autowired private RepoRepository repoRepository;
  @Autowired private HelmChartRepository helmChartRepository;
  @Autowired private HelmChartVersionRepository helmChartVersionRepository;
  @Autowired private NuGetPackageService<UUID> nuGetPackageService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("Helm lists a chart once when two of its versions share a createdAt (RPS-1307)")
  void helmChartWithTiedVersionsIsListedOnce() {
    final var repo = this.repo(RepoType.HELM, "h2helmties");
    final var chart = new HelmChart();
    chart.setRepo(repo);
    chart.setName("payments");
    final var savedChart = this.helmChartRepository.save(chart);
    for (final var version : List.of("1.0.0", "1.1.0")) {
      final var row = new HelmChartVersion();
      row.setChart(savedChart);
      row.setVersion(version);
      row.setDigest("sha256:" + UUID.randomUUID().toString().replace("-", ""));
      row.setSize(1);
      this.helmChartVersionRepository.save(row);
    }
    this.helmChartVersionRepository.flush();
    this.jdbcTemplate.update(
        "update \"public\".\"helm_chart_version\" set \"created_at\" = ? where \"chart_id\" = ?",
        Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
        savedChart.getId());
    this.helmChartVersionRepository.flush();

    final var page =
        this.helmChartVersionRepository.findLatestByRepoIdAndQuery(
            repo.getId(), "", PageRequest.of(0, 10));

    assertThat(page.getTotalElements()).isEqualTo(1);
    assertThat(page.getContent()).hasSize(1);
    assertThat(page.getContent().getFirst().getVersion())
        .isEqualTo(
            this.helmChartVersionRepository
                .findAllByChartOrderByCreatedAtDescIdDesc(savedChart)
                .getFirst()
                .getVersion());
  }

  @Test
  @DisplayName("NuGet searches a package's versions before paging (RPS-1304)")
  void nugetVersionSearchSpansPages() throws IOException {
    final var repo = this.repo(RepoType.NUGET, "h2nugetsearch");
    final var info = this.repoInfo(repo);
    for (final var version : List.of("1.0.0", "1.0.1-Beta.1", "2.0.0", "2.1.0", "2.2.0")) {
      this.nuGetPackageService.publishVersion(
          info,
          "h2.search",
          version,
          "<package><metadata><id>h2.search</id><version>"
              + version
              + "</version></metadata></package>",
          null,
          replacesExisting -> BaseUsages.ofDisk(0));
    }

    final var beta =
        this.nuGetPackageService.getVersionInfosPage(
            info, "h2.search", "BETA", PageRequest.of(0, 10));
    final var everything =
        this.nuGetPackageService.getVersionInfosPage(info, "h2.search", "", PageRequest.of(0, 10));
    final var secondPage =
        this.nuGetPackageService.getVersionInfosPage(info, "h2.search", "2.", PageRequest.of(1, 2));
    final var wildcard =
        this.nuGetPackageService.getVersionInfosPage(info, "h2.search", "%", PageRequest.of(0, 10));

    assertThat(beta.map(NuGetVersionInfo::version).getContent()).containsExactly("1.0.1-Beta.1");
    assertThat(everything.getTotalElements()).isEqualTo(5);
    assertThat(secondPage.getTotalElements()).isEqualTo(3);
    assertThat(secondPage.getContent()).hasSize(1);
    assertThat(wildcard.getTotalElements()).isZero();
  }

  private Repo repo(final RepoType type, final String name) {
    final var repo = new Repo();
    repo.setName(name);
    repo.setType(type);
    repo.setPrivateRepo(false);
    repo.setAllowOverride(true);
    repo.setSearchable(true);
    repo.setDiskUsage(0);
    repo.setCreatedAt(Instant.now());
    return this.repoRepository.saveAndFlush(repo);
  }

  private BaseRepoInfo<UUID> repoInfo(final Repo repo) {
    return BaseRepoInfo.<UUID>builder()
        .id(repo.getId())
        .name(repo.getName())
        .type(repo.getType())
        .privateRepo(repo.isPrivateRepo())
        .allowOverride(repo.isAllowOverride())
        .searchable(repo.isSearchable())
        .diskUsage(repo.getDiskUsage())
        .build();
  }
}
