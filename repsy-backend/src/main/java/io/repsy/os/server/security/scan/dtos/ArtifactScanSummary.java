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
package io.repsy.os.server.security.scan.dtos;

/**
 * One row per artifact (package), aggregated across all of that artifact's versions' latest scans.
 * {@code severityRank} is {@link Severity}'s ordinal (0=CRITICAL .. 4=UNKNOWN), matching {@link
 * RepoSeverityRank}.
 */
public interface ArtifactScanSummary {
  String getArtifactName();

  Integer getSeverityRank();

  Long getFindingCount();
}
