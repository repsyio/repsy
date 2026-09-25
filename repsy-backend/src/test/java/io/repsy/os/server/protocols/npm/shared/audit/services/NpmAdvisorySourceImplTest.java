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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.os.server.security.scan.dtos.FixStatus;
import io.repsy.os.server.security.scan.dtos.KnownVulnerabilityRow;
import io.repsy.os.server.security.scan.dtos.Severity;
import io.repsy.os.server.security.scan.services.VulnerabilityScanTxService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("NpmAdvisorySourceImpl")
class NpmAdvisorySourceImplTest {

  private final VulnerabilityScanTxService scans = mock(VulnerabilityScanTxService.class);
  private final NpmAdvisorySourceImpl source = new NpmAdvisorySourceImpl(this.scans);

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
  }

  @Test
  @DisplayName("reports the findings of the repo when its security scan is on")
  void scanOn() {
    final var repo = repo(true);
    final var row = row();
    when(this.scans.findKnownVulnerabilities(repo.getStorageKey(), Set.of("lodash")))
        .thenReturn(List.of(row));

    final var advisories = this.source.findAdvisories(repo, Map.of("lodash", Set.of("4.17.20")));

    assertThat(advisories).hasSize(1);
    assertThat(advisories.getFirst().packageName()).isEqualTo("lodash");
  }

  @Test
  @DisplayName("does not query for no packages")
  void noPackages() {
    assertThat(this.source.findAdvisories(repo(true), Map.of())).isEmpty();
    verify(this.scans, never()).findKnownVulnerabilities(any(), any());
  }
}
