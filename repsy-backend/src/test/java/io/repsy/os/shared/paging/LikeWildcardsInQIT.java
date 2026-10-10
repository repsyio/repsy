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
import io.repsy.os.AbstractIT;
import io.repsy.os.server.protocols.helm.shared.chart.entities.HelmChart;
import io.repsy.os.server.protocols.helm.shared.chart.entities.HelmChartVersion;
import io.repsy.os.server.protocols.helm.shared.chart.repositories.HelmChartRepository;
import io.repsy.os.server.protocols.helm.shared.chart.repositories.HelmChartVersionRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.entities.NpmPackage;
import io.repsy.os.server.protocols.npm.shared.npm_package.entities.PackageVersion;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.NpmPackageRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.PackageVersionRepository;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.entities.RubyGem;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.entities.RubyGemVersion;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.repositories.RubyGemRepository;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.repositories.RubyGemVersionRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A {@code _} or {@code %} in {@code q} matches itself on the npm, Ruby and Helm lists, not any
 * character (RPS-1891, the OS side of RPS-1888). The Docker lists are covered in {@code
 * DockerImageControllerIT}.
 *
 * <p>Every case seeds a row that an unescaped wildcard would match and one that only the literal
 * matches, so the test fails on a {@code like %:q%} query.
 */
@DisplayName("_ and % in q match literally (RPS-1891)")
class LikeWildcardsInQIT extends AbstractIT {

  @Autowired private NpmPackageRepository npmPackageRepository;
  @Autowired private PackageVersionRepository npmPackageVersionRepository;
  @Autowired private RubyGemRepository rubyGemRepository;
  @Autowired private RubyGemVersionRepository rubyGemVersionRepository;
  @Autowired private HelmChartRepository helmChartRepository;
  @Autowired private HelmChartVersionRepository helmChartVersionRepository;

  private List<String> names(
      final String path, final String param, final String q, final String field) throws Exception {
    final var body =
        this.perform(get(path).param(param, q).header(AUTHORIZATION, this.adminBearerToken()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    return JsonPath.read(body, "$.content[*]." + field);
  }

  private void seedNpm(
      final Repo repo, final String scope, final String name, final String... versions) {
    final var pkg = new NpmPackage();
    pkg.setRepo(this.repoRepository.getReferenceById(repo.getId()));
    pkg.setScope(scope);
    pkg.setName(name);
    pkg.setLatest(versions[0]);
    final var saved = this.npmPackageRepository.save(pkg);

    for (final var version : versions) {
      final var packageVersion = new PackageVersion();
      packageVersion.setNpmPackage(saved);
      packageVersion.setVersion(version);
      this.npmPackageVersionRepository.save(packageVersion);
    }
  }

  @Test
  @DisplayName("npm package, scope and version lists")
  void npm() throws Exception {
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("npmlike"));
    final var packages = "/api/npm/packages/" + repo.getName();
    final var scopes = "/api/npm/scopes/" + repo.getName();

    this.seedNpm(repo, null, "a_b", "1.0.0");
    this.seedNpm(repo, null, "axb", "1.0.0");
    this.seedNpm(repo, null, "vpkg", "1_0", "1x0");
    this.seedNpm(repo, "my_s", "p", "1.0.0");
    this.seedNpm(repo, "myxs", "p", "1.0.0");
    this.seedNpm(repo, "sc", "n_m", "1.0.0");
    this.seedNpm(repo, "sc", "nxm", "1.0.0");
    this.entityManager.flush();

    assertThat(this.names(packages, "scope", "a_b", "name")).containsExactly("a_b");
    assertThat(this.names(packages, "scope", "%", "name")).isEmpty();
    assertThat(this.names(packages, "scope", "my_s", "scope")).containsExactly("my_s");
    assertThat(this.names(scopes + "/packages", "q", "a_b", "name")).containsExactly("a_b");
    assertThat(this.names(scopes + "/packages", "q", "%", "name")).isEmpty();
    assertThat(this.names(scopes + "/sc/packages", "q", "n_m", "name")).containsExactly("n_m");
    assertThat(this.names(scopes + "/sc/packages", "q", "%", "name")).isEmpty();

    final var versions = packages + "/vpkg/versions";
    assertThat(this.names(versions, "q", "1_0", "version")).containsExactly("1_0");
    assertThat(this.names(versions, "q", "%", "version")).isEmpty();
    assertThat(this.names(versions + "?sort=version,desc", "q", "1_0", "version"))
        .containsExactly("1_0");
  }

  @Test
  @DisplayName("Ruby gem and version lists")
  void ruby() throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("rubylike"));
    final var gems = "/api/ruby/gems/" + repo.getName();

    this.seedGem(repo, "a_b", "1.0.0");
    this.seedGem(repo, "axb", "1.0.0");
    this.seedGem(repo, "vgem", "1_0", "1x0");
    this.entityManager.flush();

    assertThat(this.names(gems, "q", "a_b", "name")).containsExactly("a_b");
    assertThat(this.names(gems, "q", "%", "name")).isEmpty();

    assertThat(this.names(gems + "/vgem/versions", "q", "1_0", "version")).containsExactly("1_0");
    assertThat(this.names(gems + "/vgem/versions", "q", "%", "version")).isEmpty();
    assertThat(this.names(gems + "/vgem/versions?sort=version,desc", "q", "1_0", "version"))
        .containsExactly("1_0");
  }

  private void seedGem(final Repo repo, final String name, final String... versions) {
    final var gem = new RubyGem();
    gem.setRepo(this.repoRepository.getReferenceById(repo.getId()));
    gem.setName(name);
    gem.setLatest(versions[0]);
    final var saved = this.rubyGemRepository.save(gem);

    for (final var version : versions) {
      final var gemVersion = new RubyGemVersion();
      gemVersion.setGem(saved);
      gemVersion.setVersion(version);
      gemVersion.setPlatform("ruby");
      gemVersion.setChecksum(UUID.randomUUID().toString().replace("-", "").repeat(2));
      this.rubyGemVersionRepository.save(gemVersion);
    }
  }

  @Test
  @DisplayName("Helm chart and chart version lists")
  void helm() throws Exception {
    final var repo = this.seedRepo(RepoType.HELM, uniqueRepoName("helmlike"));
    final var charts = "/api/helm/charts/" + repo.getName();

    this.seedChart(repo, "a_b", "1.0.0");
    this.seedChart(repo, "axb", "1.0.0");
    this.seedChart(repo, "vchart", "1_0", "1x0");
    this.entityManager.flush();

    assertThat(this.names(charts, "q", "a_b", "name")).containsExactly("a_b");
    assertThat(this.names(charts, "q", "%", "name")).isEmpty();
    assertThat(this.names(charts + "/vchart/versions", "q", "1_0", "version"))
        .containsExactly("1_0");
    assertThat(this.names(charts + "/vchart/versions", "q", "%", "version")).isEmpty();
  }

  private void seedChart(final Repo repo, final String name, final String... versions) {
    final var chart = new HelmChart();
    chart.setRepo(this.repoRepository.getReferenceById(repo.getId()));
    chart.setName(name);
    final var saved = this.helmChartRepository.save(chart);

    for (final var version : versions) {
      final var chartVersion = new HelmChartVersion();
      chartVersion.setChart(saved);
      chartVersion.setVersion(version);
      chartVersion.setDigest("sha256:" + UUID.randomUUID().toString().replace("-", ""));
      chartVersion.setSize(1);
      this.helmChartVersionRepository.save(chartVersion);
    }
  }
}
