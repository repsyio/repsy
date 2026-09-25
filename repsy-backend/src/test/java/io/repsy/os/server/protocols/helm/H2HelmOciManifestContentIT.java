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
package io.repsy.os.server.protocols.helm;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.H2IntegrationTest;
import io.repsy.os.server.protocols.helm.shared.chart.entities.HelmChart;
import io.repsy.os.server.protocols.helm.shared.chart.entities.HelmChartVersion;
import io.repsy.os.server.protocols.helm.shared.chart.repositories.HelmChartRepository;
import io.repsy.os.server.protocols.helm.shared.chart.repositories.HelmChartVersionRepository;
import io.repsy.os.server.protocols.helm.shared.oci.entities.HelmOciManifest;
import io.repsy.os.server.protocols.helm.shared.oci.repositories.HelmOciManifestRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * RPS-1392: {@code helm_oci_manifest.content} is mapped without {@code @Lob}. On PostgreSQL that
 * keeps the JSON in the column; on embedded H2 (a clob there) it has to read and write the string
 * all the same.
 */
@DisplayName("Helm OCI manifest content on embedded H2 (RPS-1392)")
class H2HelmOciManifestContentIT extends H2IntegrationTest {

  private static final String CONTENT = "{\"schemaVersion\":2,\"note\":\"Çalışma 日本語\"}";

  @Autowired private EntityManager entityManager;
  @Autowired private RepoRepository repoRepository;
  @Autowired private HelmChartRepository helmChartRepository;
  @Autowired private HelmChartVersionRepository helmChartVersionRepository;
  @Autowired private HelmOciManifestRepository helmOciManifestRepository;

  @Test
  @DisplayName("a saved manifest reads back as the same JSON")
  void roundTripsTheContent() {
    final var repo = new Repo();
    repo.setName("h2helmmanifest");
    repo.setType(RepoType.HELM);
    repo.setPrivateRepo(false);
    repo.setAllowOverride(true);
    repo.setDiskUsage(0);
    repo.setCreatedAt(Instant.now());
    final var savedRepo = this.repoRepository.saveAndFlush(repo);

    final var chart = new HelmChart();
    chart.setRepo(savedRepo);
    chart.setName("payments");
    final var savedChart = this.helmChartRepository.save(chart);
    final var version = new HelmChartVersion();
    version.setChart(savedChart);
    version.setVersion("1.0.0");
    version.setDigest("sha256:" + "a".repeat(64));
    version.setSize(1);
    final var savedVersion = this.helmChartVersionRepository.save(version);

    final var manifest = new HelmOciManifest();
    manifest.setRepo(savedRepo);
    manifest.setChartVersion(savedVersion);
    manifest.setName("payments");
    manifest.setReference("1.0.0");
    manifest.setDigest("sha256:" + "b".repeat(64));
    manifest.setMediaType("application/vnd.oci.image.manifest.v1+json");
    manifest.setContent(CONTENT);
    this.helmOciManifestRepository.saveAndFlush(manifest);
    this.entityManager.clear();

    assertThat(
            this.helmOciManifestRepository
                .findByRepoIdAndNameAndReference(savedRepo.getId(), "payments", "1.0.0")
                .orElseThrow()
                .getContent())
        .isEqualTo(CONTENT);
    try (final var contents =
        this.helmOciManifestRepository.streamContentByRepoId(savedRepo.getId())) {
      assertThat(contents).containsExactly(CONTENT);
    }
  }
}
