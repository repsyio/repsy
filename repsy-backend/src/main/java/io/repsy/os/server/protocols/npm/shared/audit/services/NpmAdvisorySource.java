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

import io.repsy.os.server.security.scan.dtos.FixStatus;
import io.repsy.os.server.security.scan.dtos.KnownVulnerabilityRow;
import io.repsy.os.server.security.scan.dtos.Severity;
import io.repsy.os.server.security.scan.services.VulnerabilityScanTxService;
import io.repsy.os.server.security.scanner.VulnerabilityAdvisoryLookup;
import io.repsy.os.server.security.scanner.dtos.AdvisoryLookupResult;
import io.repsy.os.server.security.scanner.dtos.ScannerFinding;
import io.repsy.protocols.npm.shared.audit.AbstractNpmAdvisorySource;
import io.repsy.protocols.npm.shared.audit.NpmAdvisory;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The advisories of an npm audit are what the scanner's vulnerability database knows about the
 * versions the audit asks about (the lookup, RPS-1612) together with the findings of the scans of
 * the repository the audit was sent to.
 *
 * <p>A repository whose security scan setting is off reports none, whatever either source holds.
 * The lookup is an addition: when the scanner does not answer (it is disabled, busy, slow or down)
 * the audit is answered from the stored findings alone, as it was before there was a lookup, and
 * the client never sees the difference but for the findings it does not get. A finding both sources
 * report is reported once, as the stored one, so a scan of the repository wins over the lookup.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@NullMarked
public class NpmAdvisorySource extends AbstractNpmAdvisorySource<UUID> {

  private static final String LOOKUP_SCANNER_NAME = "trivy";

  private final VulnerabilityScanTxService scanService;
  private final VulnerabilityAdvisoryLookup advisoryLookup;

  @Override
  public List<NpmAdvisory> findAdvisories(
      final BaseRepoInfo<UUID> repoInfo, final Map<String, Set<String>> versionsByName) {

    // Findings of scans that ran before the setting was turned off stay in the database, but a
    // repository that is not scanned reports nothing, as the README says.
    if (!repoInfo.isSecurityScanEnabled() || versionsByName.isEmpty()) {
      return List.of();
    }

    final var stored =
        this.scanService.findKnownVulnerabilities(repoInfo.getStorageKey(), versionsByName);
    final var looked = this.lookup(versionsByName);

    return NpmAdvisoryMapper.toAdvisories(merge(stored, looked), versionsByName);
  }

  private List<KnownVulnerabilityRow> lookup(final Map<String, Set<String>> versionsByName) {
    try {
      return this.advisoryLookup
          .lookupNpm(versionsByName)
          .map(NpmAdvisorySource::rows)
          .orElse(List.of());
    } catch (final RuntimeException exception) {
      // The lookup does not throw, but an audit must not fail on one that does.
      log.warn("npm advisory lookup failed, using the stored findings only", exception);
      return List.of();
    }
  }

  private static List<KnownVulnerabilityRow> rows(final AdvisoryLookupResult result) {
    final var completedAt = result.dbUpdatedAt() == null ? Instant.EPOCH : result.dbUpdatedAt();

    // A finding that says the version is not affected is no advisory, as in the stored query.
    return result.findings().stream()
        .filter(finding -> finding.fixStatus() != FixStatus.NOT_AFFECTED)
        .<KnownVulnerabilityRow>map(finding -> new LookupRow(finding, completedAt))
        .toList();
  }

  /** The stored findings and those of the lookup that no stored finding says already. */
  static List<KnownVulnerabilityRow> merge(
      final List<KnownVulnerabilityRow> stored, final List<KnownVulnerabilityRow> looked) {

    if (looked.isEmpty()) {
      return stored;
    }

    final var byKey = new LinkedHashMap<RowKey, KnownVulnerabilityRow>();

    for (final var row : stored) {
      byKey.putIfAbsent(RowKey.of(row), row);
    }

    for (final var row : looked) {
      byKey.putIfAbsent(RowKey.of(row), row);
    }

    return new ArrayList<>(byKey.values());
  }

  private record RowKey(String cveId, String packageName, String packageVersion) {

    static RowKey of(final KnownVulnerabilityRow row) {
      return new RowKey(row.getCveId(), row.getPackageName(), row.getPackageVersion());
    }
  }

  /** A finding of the lookup, read like a stored one; it has no scan, so the database date. */
  private record LookupRow(ScannerFinding finding, Instant completedAt)
      implements KnownVulnerabilityRow {

    @Override
    public String getCveId() {
      return this.finding.cveId();
    }

    @Override
    public Severity getSeverity() {
      return this.finding.severity();
    }

    @Override
    public String getPackageName() {
      return this.finding.packageName();
    }

    @Override
    public String getPackageVersion() {
      return this.finding.packageVersion();
    }

    @Override
    public @Nullable String getFixedVersion() {
      return this.finding.fixedVersion();
    }

    @Override
    public @Nullable String getDescription() {
      return this.finding.description();
    }

    @Override
    public @Nullable String getReferenceUrl() {
      return this.finding.referenceUrl();
    }

    @Override
    public FixStatus getFixStatus() {
      return this.finding.fixStatus();
    }

    @Override
    public @Nullable Double getCvssScore() {
      return this.finding.cvssScore();
    }

    @Override
    public @Nullable String getCvssVector() {
      return this.finding.cvssVector();
    }

    @Override
    public Instant getCompletedAt() {
      return this.completedAt;
    }

    @Override
    public String getScannerName() {
      return LOOKUP_SCANNER_NAME;
    }
  }
}
