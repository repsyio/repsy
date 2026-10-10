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
package io.repsy.os.server.protocols.npm.shared.audit.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.libs.scanner.VulnerabilityAdvisoryLookup;
import io.repsy.libs.scanner.dtos.AdvisoryLookupResult;
import io.repsy.libs.scanner.dtos.FixStatus;
import io.repsy.libs.scanner.dtos.ScannerFinding;
import io.repsy.libs.scanner.dtos.Severity;
import io.repsy.os.server.security.scan.dtos.KnownVulnerabilityRow;
import io.repsy.os.server.security.scan.services.VulnerabilityScanTxService;
import io.repsy.protocols.npm.shared.audit.NpmSeverity;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("NpmAdvisorySource")
class NpmAdvisorySourceTest {

  private final VulnerabilityScanTxService scans = mock(VulnerabilityScanTxService.class);
  private final VulnerabilityAdvisoryLookup lookup = mock(VulnerabilityAdvisoryLookup.class);
  private final NpmAdvisorySource source = new NpmAdvisorySource(this.scans, this.lookup);

  {
    when(this.lookup.lookupNpm(any())).thenReturn(Optional.empty());
  }

  private static BaseRepoInfo<UUID> repo(final boolean scanEnabled) {
    return BaseRepoInfo.<UUID>builder()
        .name("npm")
        .storageKey(UUID.randomUUID())
        .securityScanEnabled(scanEnabled)
        .build();
  }

  private static KnownVulnerabilityRow row() {
    final var row = mock(KnownVulnerabilityRow.class);
    when(row.getCveId()).thenReturn("CVE-1");
    when(row.getSeverity()).thenReturn(Severity.HIGH);
    when(row.getPackageName()).thenReturn("lodash");
    when(row.getPackageVersion()).thenReturn("4.17.20");
    when(row.getFixStatus()).thenReturn(FixStatus.FIXED);
    when(row.getCompletedAt()).thenReturn(Instant.EPOCH);
    return row;
  }

  @Test
  @DisplayName("reports nothing, and does not query, when the security scan of the repo is off")
  void scanOff() {
    final var advisories =
        this.source.findAdvisories(repo(false), Map.of("lodash", Set.of("4.17.20")));

    assertThat(advisories).isEmpty();
    verify(this.scans, never()).findKnownVulnerabilities(any(), any());
    verify(this.lookup, never()).lookupNpm(any());
  }

  @Test
  @DisplayName("reports the findings of the repo when its security scan is on")
  void scanOn() {
    final var repo = repo(true);
    final var row = row();
    final var requested = Map.of("lodash", Set.of("4.17.20"));
    when(this.scans.findKnownVulnerabilities(repo.getStorageKey(), requested))
        .thenReturn(List.of(row));

    final var advisories = this.source.findAdvisories(repo, requested);

    assertThat(advisories).hasSize(1);
    assertThat(advisories.getFirst().packageName()).isEqualTo("lodash");
  }

  @Test
  @DisplayName("does not query for no packages")
  void noPackages() {
    assertThat(this.source.findAdvisories(repo(true), Map.of())).isEmpty();
    verify(this.scans, never()).findKnownVulnerabilities(any(), any());
    verify(this.lookup, never()).lookupNpm(any());
  }

  private static ScannerFinding finding(
      final String cveId, final String name, final String version, final FixStatus fixStatus) {
    return new ScannerFinding(
        cveId,
        Severity.HIGH,
        name,
        version,
        "9.9.9",
        "from the scanner",
        null,
        fixStatus,
        null,
        null);
  }

  private static AdvisoryLookupResult answer(final ScannerFinding... findings) {
    return new AdvisoryLookupResult(Instant.parse("2026-09-26T19:03:57Z"), List.of(findings));
  }

  @Test
  @DisplayName("reports what the lookup knows about a pair that no stored scan has")
  void lookupAddsAPairNoScanKnows() {
    final var repo = repo(true);
    final var requested = Map.of("lodash", Set.of("4.17.20"), "minimist", Set.of("1.2.0"));
    final var stored = row();
    when(this.scans.findKnownVulnerabilities(repo.getStorageKey(), requested))
        .thenReturn(List.of(stored));
    when(this.lookup.lookupNpm(requested))
        .thenReturn(Optional.of(answer(finding("CVE-2", "minimist", "1.2.0", FixStatus.FIXED))));

    final var advisories = this.source.findAdvisories(repo, requested);

    assertThat(advisories)
        .extracting(a -> a.packageName())
        .containsExactlyInAnyOrder("lodash", "minimist");
    final var minimist =
        advisories.stream()
            .filter(a -> a.packageName().equals("minimist"))
            .findFirst()
            .orElseThrow();
    assertThat(minimist.vulnerableVersions()).containsExactly("1.2.0");
    assertThat(minimist.reportedBy()).isEqualTo("trivy");
    assertThat(minimist.updated()).isEqualTo(Instant.parse("2026-09-26T19:03:57Z"));
  }

  @Test
  @DisplayName("reports a finding of both sources once, and the stored one wins")
  void storedWinsOverTheLookup() {
    final var repo = repo(true);
    final var requested = Map.of("lodash", Set.of("4.17.20"));
    final var stored = row();
    when(this.scans.findKnownVulnerabilities(repo.getStorageKey(), requested))
        .thenReturn(List.of(stored));
    when(this.lookup.lookupNpm(requested))
        .thenReturn(Optional.of(answer(finding("CVE-1", "lodash", "4.17.20", FixStatus.FIXED))));

    final var advisories = this.source.findAdvisories(repo, requested);

    assertThat(advisories).hasSize(1);
    // The stored row has no description or fix, the lookup's has: the stored one is reported.
    assertThat(advisories.getFirst().overview()).isEmpty();
    assertThat(advisories.getFirst().vulnerableVersions()).containsExactly("4.17.20");
    assertThat(advisories.getFirst().patchedVersions()).isNull();
  }

  @Test
  @DisplayName("a finding of the lookup for a version that was not asked about is not reported")
  void lookupFindingsOfOtherVersionsAreIgnored() {
    final var repo = repo(true);
    final var requested = Map.of("lodash", Set.of("4.17.20"));
    when(this.scans.findKnownVulnerabilities(repo.getStorageKey(), requested))
        .thenReturn(List.of());
    when(this.lookup.lookupNpm(requested))
        .thenReturn(Optional.of(answer(finding("CVE-2", "lodash", "4.17.19", FixStatus.FIXED))));

    assertThat(this.source.findAdvisories(repo, requested)).isEmpty();
  }

  @Test
  @DisplayName("a finding of the lookup that says the version is not affected is not reported")
  void notAffectedFindingsOfTheLookupAreIgnored() {
    final var repo = repo(true);
    final var requested = Map.of("lodash", Set.of("4.17.20"));
    when(this.lookup.lookupNpm(requested))
        .thenReturn(
            Optional.of(answer(finding("CVE-2", "lodash", "4.17.20", FixStatus.NOT_AFFECTED))));

    assertThat(this.source.findAdvisories(repo, requested)).isEmpty();
  }

  @Test
  @DisplayName("answers from the stored findings alone when the lookup has no answer")
  void noLookupAnswer() {
    final var repo = repo(true);
    final var requested = Map.of("lodash", Set.of("4.17.20"));
    final var stored = row();
    when(this.scans.findKnownVulnerabilities(repo.getStorageKey(), requested))
        .thenReturn(List.of(stored));
    when(this.lookup.lookupNpm(requested)).thenReturn(Optional.empty());

    assertThat(this.source.findAdvisories(repo, requested)).hasSize(1);
  }

  @Test
  @DisplayName("answers from the stored findings alone, and does not fail, when the lookup throws")
  void lookupThrows() {
    final var repo = repo(true);
    final var requested = Map.of("lodash", Set.of("4.17.20"));
    final var stored = row();
    when(this.scans.findKnownVulnerabilities(repo.getStorageKey(), requested))
        .thenReturn(List.of(stored));
    when(this.lookup.lookupNpm(requested)).thenThrow(new IllegalStateException("scanner is down"));

    assertThat(this.source.findAdvisories(repo, requested)).hasSize(1);
  }

  @ParameterizedTest
  @CsvSource({"CRITICAL, CRITICAL", "HIGH, HIGH", "MEDIUM, MODERATE", "LOW, LOW", "UNKNOWN, LOW"})
  @DisplayName("maps Trivy severities to npm severities, and an unknown one to low, never info")
  void severities(final Severity trivy, final NpmSeverity npm) {
    assertThat(NpmAdvisorySource.toNpmSeverity(trivy)).isEqualTo(npm);
  }

  @Test
  @DisplayName("refuses a missing severity")
  void missingSeverity() {
    assertThatThrownBy(() -> NpmAdvisorySource.toNpmSeverity(null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
