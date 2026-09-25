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

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.security.scan.dtos.FixStatus;
import io.repsy.os.server.security.scan.dtos.KnownVulnerabilityRow;
import io.repsy.os.server.security.scan.dtos.ScanStatus;
import io.repsy.os.server.security.scan.dtos.Severity;
import io.repsy.os.server.security.scan.entities.VulnerabilityFinding;
import io.repsy.os.server.security.scan.entities.VulnerabilityScan;
import io.repsy.os.server.security.scan.repositories.VulnerabilityFindingRepository;
import io.repsy.os.server.security.scan.repositories.VulnerabilityScanRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * RPS-1345: an npm audit reads the findings of the requested versions only, not the description of
 * every scanned version of a common package, and the lookup by package has an index.
 */
@DisplayName("Findings of the requested package versions (npm audit lookup)")
class KnownVulnerabilitiesIT extends AbstractIntegrationTest {

  private static final String INDEX = "ix_vulnerability_finding__package_name_package_version";
  private static final String LODASH = "lodash";

  @Autowired private VulnerabilityScanTxService service;
  @Autowired private VulnerabilityScanRepository scanRepository;
  @Autowired private VulnerabilityFindingRepository findingRepository;

  private Repo repo() {
    return this.seedRepo(RepoType.NPM, uniqueRepoName("known"));
  }

  private void seedFinding(
      final Repo repo, final String cve, final String packageName, final String packageVersion) {

    final var scan = new VulnerabilityScan();
    scan.setRepo(repo);
    scan.setArtifactName("app-" + packageName + "-" + packageVersion);
    scan.setArtifactVersion("1.0.0");
    scan.setStatus(ScanStatus.COMPLETED);
    scan.setScannerName("trivy");
    scan.setCompletedAt(Instant.parse("2026-09-01T10:00:30Z"));
    final var saved = this.scanRepository.saveAndFlush(scan);

    final var finding = new VulnerabilityFinding();
    finding.setScan(saved);
    finding.setCveId(cve);
    finding.setSeverity(Severity.HIGH);
    finding.setPackageName(packageName);
    finding.setPackageVersion(packageVersion);
    finding.setFixedVersion("9.9.9");
    finding.setDescription("A long description of " + cve + " in " + packageVersion);
    finding.setFixStatus(FixStatus.FIXED);
    this.findingRepository.saveAndFlush(finding);
  }

  private static List<String> pairs(final List<KnownVulnerabilityRow> rows) {
    return rows.stream().map(row -> row.getPackageName() + "@" + row.getPackageVersion()).toList();
  }

  @Test
  @DisplayName("returns nothing of the other scanned versions of a requested package")
  void otherVersionsAreNotRead() {
    final var repo = this.repo();
    IntStream.range(0, 40).forEach(i -> this.seedFinding(repo, "CVE-2021-1", LODASH, "4.17." + i));

    final var rows =
        this.service.findKnownVulnerabilities(repo.getId(), Map.of(LODASH, Set.of("4.17.7")));

    assertThat(pairs(rows)).containsExactly("lodash@4.17.7");
    assertThat(rows.getFirst().getDescription()).contains("4.17.7");
  }

  @Test
  @DisplayName("matches the pair, not a version that was requested for another package")
  void matchesThePairs() {
    final var repo = this.repo();
    this.seedFinding(repo, "CVE-1", LODASH, "4.17.21");
    this.seedFinding(repo, "CVE-2", "left-pad", "4.17.21");
    this.seedFinding(repo, "CVE-3", "left-pad", "1.3.0");

    final var rows =
        this.service.findKnownVulnerabilities(
            repo.getId(), Map.of(LODASH, Set.of("4.17.21"), "left-pad", Set.of("1.3.0")));

    assertThat(pairs(rows)).containsExactlyInAnyOrder("lodash@4.17.21", "left-pad@1.3.0");
  }

  @Test
  @DisplayName("never returns the findings of another repo")
  void otherRepo() {
    final var repo = this.repo();
    final var other = this.repo();
    this.seedFinding(other, "CVE-1", LODASH, "4.17.21");

    assertThat(
            this.service.findKnownVulnerabilities(repo.getId(), Map.of(LODASH, Set.of("4.17.21"))))
        .isEmpty();
  }

  @Test
  @DisplayName("looks a package up through the index on package_name, package_version")
  void usesTheIndex() {
    // The test runs in a transaction that is rolled back, so the setting does not outlive it. A
    // few rows are read with a sequential scan, so it is switched off to show that the query can
    // use the index at all, and how.
    this.jdbcTemplate.execute("set local enable_seqscan = off");

    final var plan =
        String.join(
            "\n",
            this.jdbcTemplate.queryForList(
                """
                explain select * from vulnerability_finding
                where package_name in ('lodash', 'left-pad')
                  and package_version in ('4.17.21', '1.3.0')
                  and package_name || '@' || package_version
                    in ('lodash@4.17.21', 'left-pad@1.3.0')""",
                String.class));

    assertThat(plan).contains(INDEX).contains("package_name").contains("package_version");
  }
}
