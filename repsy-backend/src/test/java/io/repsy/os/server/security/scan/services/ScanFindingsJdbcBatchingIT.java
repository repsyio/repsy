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
package io.repsy.os.server.security.scan.services;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.libs.scanner.dtos.FixStatus;
import io.repsy.libs.scanner.dtos.ScanOutcome;
import io.repsy.libs.scanner.dtos.ScannerFinding;
import io.repsy.libs.scanner.dtos.Severity;
import io.repsy.os.AbstractIT;
import io.repsy.os.server.security.scan.dtos.ScanStatus;
import io.repsy.os.server.security.scan.entities.VulnerabilityScan;
import io.repsy.os.server.security.scan.repositories.VulnerabilityFindingRepository;
import io.repsy.os.server.security.scan.repositories.VulnerabilityScanRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.stream.IntStream;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves that the findings of a scan are written in JDBC batches (RPS-2118): {@code
 * spring.jpa.properties.hibernate.jdbc.batch_size} is 50, so 2,000 findings take about 40 prepared
 * statements instead of 2,000. It runs inside the test transaction, so nothing is committed.
 */
class ScanFindingsJdbcBatchingIT extends AbstractIT {

  private static final int FINDINGS = 2_000;
  private static final int BATCH_SIZE = 50;

  @Autowired private RepoTxService repoTxService;
  @Autowired private VulnerabilityScanRepository scanRepository;
  @Autowired private VulnerabilityFindingRepository findingRepository;
  @Autowired private VulnerabilityScanTxService scanTxService;
  @Autowired private EntityManager entityManager;
  @Autowired private EntityManagerFactory entityManagerFactory;

  @Test
  @DisplayName("recordScanOutcome inserts 2000 findings in batches of 50, not one by one")
  void recordScanOutcomeBatchesFindingInserts() {

    final var repo = this.createRepo();
    final var scan = new VulnerabilityScan();
    scan.setRepo(repo);
    scan.setArtifactName("app");
    scan.setArtifactVersion("1.0.0");
    scan.setStatus(ScanStatus.RUNNING);
    scan.setScannerName("trivy");
    scan.setStartedAt(Instant.now());
    final var scanId = this.scanRepository.saveAndFlush(scan).getId();

    final var findings =
        IntStream.range(0, FINDINGS)
            .mapToObj(
                i ->
                    new ScannerFinding(
                        "CVE-2026-" + i,
                        Severity.HIGH,
                        "pkg-" + i,
                        "1.0." + i,
                        null,
                        null,
                        null,
                        FixStatus.FIXED,
                        null,
                        null))
            .toList();

    final var statistics = this.entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    final var wasEnabled = statistics.isStatisticsEnabled();
    statistics.setStatisticsEnabled(true);

    try {
      statistics.clear();

      this.scanTxService.recordScanOutcome(scanId, new ScanOutcome(findings, "0.58.1"));
      this.entityManager.flush();

      final var prepared = statistics.getPrepareStatementCount();

      assertThat(this.findingRepository.count()).isGreaterThanOrEqualTo(FINDINGS);
      // 40 insert batches plus the single update of the scan row; unbatched it is 2,001.
      assertThat(prepared).isLessThanOrEqualTo(FINDINGS / BATCH_SIZE + 10);
    } finally {
      statistics.setStatisticsEnabled(wasEnabled);
    }
  }

  private Repo createRepo() {
    final var name = uniqueRepoName("batch");
    final var info = this.repoTxService.createRepo(name, RepoType.DOCKER, true, null);
    return this.repoRepository.findById(info.getId()).orElseThrow();
  }
}
