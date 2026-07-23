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
package io.repsy.scanner.trivy.services;

import io.repsy.scanner.trivy.dtos.FixStatus;
import io.repsy.scanner.trivy.dtos.ScannerFinding;
import io.repsy.scanner.trivy.dtos.Severity;
import io.repsy.scanner.trivy.dtos.trivy.TrivyCvss;
import io.repsy.scanner.trivy.dtos.trivy.TrivyResult;
import io.repsy.scanner.trivy.dtos.trivy.TrivyVulnerability;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

final class TrivyFindingMapper {

  private static final List<String> CVSS_SOURCE_PRIORITY = List.of("nvd", "redhat", "ghsa");

  private TrivyFindingMapper() {}

  static @NonNull List<ScannerFinding> toFindings(final @Nullable List<TrivyResult> results) {
    if (results == null) {
      return List.of();
    }

    return results.stream()
        .map(TrivyResult::vulnerabilities)
        .filter(Objects::nonNull)
        .flatMap(List::stream)
        .map(TrivyFindingMapper::toFinding)
        .toList();
  }

  private static @NonNull ScannerFinding toFinding(
      final @NonNull TrivyVulnerability vulnerability) {
    final var cvss = selectCvss(vulnerability.cvss());

    return new ScannerFinding(
        vulnerability.vulnerabilityId(),
        toSeverity(vulnerability.severity()),
        vulnerability.pkgName(),
        vulnerability.installedVersion(),
        vulnerability.fixedVersion(),
        vulnerability.description(),
        vulnerability.primaryUrl(),
        toFixStatus(vulnerability.status()),
        cvss.map(SelectedCvss::score).orElse(null),
        cvss.map(SelectedCvss::vector).orElse(null));
  }

  private static @NonNull Severity toSeverity(final @Nullable String severity) {
    if (severity == null) {
      return Severity.UNKNOWN;
    }

    try {
      return Severity.valueOf(severity);
    } catch (final IllegalArgumentException exception) {
      return Severity.UNKNOWN;
    }
  }

  private static @NonNull FixStatus toFixStatus(final @Nullable String status) {
    if (status == null) {
      return FixStatus.UNKNOWN;
    }

    try {
      return FixStatus.valueOf(status.toUpperCase(Locale.ROOT));
    } catch (final IllegalArgumentException exception) {
      return FixStatus.UNKNOWN;
    }
  }

  private static @NonNull Optional<SelectedCvss> selectCvss(
      final @Nullable Map<String, TrivyCvss> cvssMap) {

    if (cvssMap == null || cvssMap.isEmpty()) {
      return Optional.empty();
    }

    for (final var source : CVSS_SOURCE_PRIORITY) {
      final var entry = cvssMap.get(source);
      if (entry != null) {
        return Optional.of(pickScore(entry));
      }
    }

    return cvssMap.values().stream().findFirst().map(TrivyFindingMapper::pickScore);
  }

  private static @NonNull SelectedCvss pickScore(final @NonNull TrivyCvss cvss) {
    if (cvss.v3Score() != null) {
      return new SelectedCvss(cvss.v3Score(), cvss.v3Vector());
    }

    return new SelectedCvss(cvss.v2Score(), cvss.v2Vector());
  }

  private record SelectedCvss(@Nullable Double score, @Nullable String vector) {}
}
