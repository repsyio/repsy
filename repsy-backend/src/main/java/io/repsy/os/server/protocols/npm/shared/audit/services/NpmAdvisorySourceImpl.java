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

import io.repsy.os.server.security.scan.services.VulnerabilityScanTxService;
import io.repsy.protocols.npm.shared.audit.NpmAdvisory;
import io.repsy.protocols.npm.shared.audit.NpmAdvisorySource;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Service;

/**
 * The advisories of an npm audit come from the vulnerability scans of the repository the audit was
 * sent to, so a repository whose security scan setting is off, or that has not been scanned yet,
 * reports none.
 */
@Service
@RequiredArgsConstructor
@NullMarked
public class NpmAdvisorySourceImpl implements NpmAdvisorySource<UUID> {

  private final VulnerabilityScanTxService scanService;

  @Override
  public List<NpmAdvisory> findAdvisories(
      final BaseRepoInfo<UUID> repoInfo, final Map<String, Set<String>> versionsByName) {

    // Findings of scans that ran before the setting was turned off stay in the database, but a
    // repository that is not scanned reports nothing, as the README says.
    if (!repoInfo.isSecurityScanEnabled() || versionsByName.isEmpty()) {
      return List.of();
    }

    final var rows =
        this.scanService.findKnownVulnerabilities(repoInfo.getStorageKey(), versionsByName);

    return NpmAdvisoryMapper.toAdvisories(rows, versionsByName);
  }
}
