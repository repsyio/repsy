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

import io.repsy.os.server.security.scan.dtos.KnownVulnerabilityRow;
import io.repsy.os.server.security.scan.dtos.Severity;
import io.repsy.protocols.npm.shared.audit.NpmAdvisory;
import io.repsy.protocols.npm.shared.audit.NpmSeverity;
import io.repsy.protocols.npm.shared.utils.NpmSemver;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Turns what the scanner found into npm advisories. Repsy knows a vulnerability only for the
 * versions it scanned, so an advisory lists exactly those versions as vulnerable, and never a range
 * such as {@code <1.2.3}, which would flag versions that were not scanned.
 */
@UtilityClass
@NullMarked
public class NpmAdvisoryMapper {

  static final int MAX_TITLE_LENGTH = 150;

  private static final int ID_BYTES = 6;
  private static final String ELLIPSIS = "...";
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private static final Pattern LINE_BREAK = Pattern.compile("\\R");

  record Key(String cveId, String packageName) {}

  /**
   * Builds the advisories of the findings. Only the findings on a requested version count, and
   * everything is computed from those: a vulnerability found in {@code 1.0.0} (fixed in {@code
   * 1.0.5}) and in {@code 2.0.0} (fixed in {@code 2.0.3}) is reported for a client that uses only
   * {@code 2.0.0} as vulnerable in {@code 2.0.0} and patched in {@code >=2.0.3}. A vulnerability is
   * one advisory for each package it affects.
   *
   * @param rows The findings on the requested packages
   * @param versionsByName The requested versions by package name
   */
  public static List<NpmAdvisory> toAdvisories(
      final List<KnownVulnerabilityRow> rows, final Map<String, Set<String>> versionsByName) {

    final var groups = new LinkedHashMap<Key, List<KnownVulnerabilityRow>>();

    for (final var row : rows) {
      final var requested = versionsByName.getOrDefault(row.getPackageName(), Set.of());

      if (requested.contains(row.getPackageVersion())) {
        groups
            .computeIfAbsent(new Key(row.getCveId(), row.getPackageName()), _ -> new ArrayList<>())
            .add(row);
      }
    }

    final var advisories = new ArrayList<NpmAdvisory>();

    for (final var group : groups.entrySet()) {
      final var vulnerable = vulnerableVersions(group.getValue());

      if (!vulnerable.isEmpty()) {
        advisories.add(build(group.getKey(), group.getValue(), vulnerable));
      }
    }

    return advisories;
  }

  /** The requested versions the vulnerability was found in, oldest first; only semver ones. */
  private static List<String> vulnerableVersions(final List<KnownVulnerabilityRow> group) {
    return group.stream()
        .map(KnownVulnerabilityRow::getPackageVersion)
        .distinct()
        .filter(NpmSemver::isValid)
        .sorted(Comparator.comparing(NpmSemver::parse))
        .toList();
  }

  private static NpmAdvisory build(
      final Key key, final List<KnownVulnerabilityRow> group, final List<String> vulnerable) {

    final var patched = patchedVersion(group, vulnerable.getFirst());
    final var description = firstNonBlank(group, KnownVulnerabilityRow::getDescription);
    final var referenceUrl = firstNonBlank(group, KnownVulnerabilityRow::getReferenceUrl);
    final var newest = Optional.ofNullable(newestScan(group));
    final var cvss =
        group.stream()
            .filter(row -> row.getCvssScore() != null)
            .max(Comparator.comparingDouble(KnownVulnerabilityRow::getCvssScore));

    return new NpmAdvisory(
        id(key),
        key.packageName(),
        title(key.cveId(), description),
        url(key.cveId(), referenceUrl),
        worstSeverity(group),
        vulnerable,
        patched == null ? null : ">=" + patched,
        key.cveId().startsWith("CVE-") ? List.of(key.cveId()) : List.of(),
        key.cveId().startsWith("GHSA-") ? key.cveId() : null,
        Objects.requireNonNullElse(description, ""),
        recommendation(patched),
        Objects.requireNonNullElse(referenceUrl, ""),
        cvss.map(KnownVulnerabilityRow::getCvssScore).orElse(null),
        cvss.map(KnownVulnerabilityRow::getCvssVector).orElse(null),
        newest.map(KnownVulnerabilityRow::getCompletedAt).orElse(Instant.EPOCH),
        newest.map(KnownVulnerabilityRow::getScannerName).orElse(null));
  }

  private static String recommendation(final @Nullable String patched) {
    return patched == null
        ? "No fix is available yet."
        : "Upgrade to version " + patched + " or later.";
  }

  /** The maps of severities: Trivy's {@code MEDIUM} is npm's {@code moderate}. */
  static NpmSeverity toNpmSeverity(final @Nullable Severity severity) {
    return switch (severity) {
      case CRITICAL -> NpmSeverity.CRITICAL;
      case HIGH -> NpmSeverity.HIGH;
      case MEDIUM -> NpmSeverity.MODERATE;
      case LOW, UNKNOWN -> NpmSeverity.LOW;
      case null, default -> throw new IllegalArgumentException("unknown severity " + severity);
    };
  }

  /**
   * A number that stays the same for a vulnerability and a package, is different for another
   * vulnerability or package, and is a safe integer in JavaScript (48 bits, and never 0).
   */
  static long id(final Key key) {
    try {
      final var digest =
          MessageDigest.getInstance("SHA-256")
              .digest((key.cveId() + "\n" + key.packageName()).getBytes(StandardCharsets.UTF_8));

      return new BigInteger(1, Arrays.copyOf(digest, ID_BYTES)).longValue() + 1;
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
    }
  }

  static String url(final String cveId, final @Nullable String referenceUrl) {
    if (referenceUrl != null) {
      return referenceUrl;
    }

    if (cveId.startsWith("CVE-")) {
      return "https://nvd.nist.gov/vuln/detail/" + cveId;
    }

    if (cveId.startsWith("GHSA-")) {
      return "https://github.com/advisories/" + cveId;
    }

    return "";
  }

  static String title(final String cveId, final @Nullable String description) {
    if (description == null) {
      return cveId;
    }

    final var firstLine = LINE_BREAK.split(description.strip(), 2)[0];
    var summary = WHITESPACE.matcher(firstLine).replaceAll(" ").strip();

    if (summary.length() > MAX_TITLE_LENGTH) {
      summary = summary.substring(0, MAX_TITLE_LENGTH - ELLIPSIS.length()) + ELLIPSIS;
    }

    return cveId + ": " + summary;
  }

  private static NpmSeverity worstSeverity(final List<KnownVulnerabilityRow> group) {
    return group.stream()
        .map(row -> toNpmSeverity(row.getSeverity()))
        .max(Comparator.naturalOrder())
        .orElse(NpmSeverity.LOW);
  }

  /**
   * The smallest fixed version that is above the smallest vulnerable version. A finding lists its
   * fixes as {@code "1.2.3, 2.0.1"}, one for each maintained branch.
   */
  private static @Nullable String patchedVersion(
      final List<KnownVulnerabilityRow> group, final String smallestVulnerable) {

    final var floor = NpmSemver.parse(smallestVulnerable);

    return group.stream()
        .map(KnownVulnerabilityRow::getFixedVersion)
        .filter(Objects::nonNull)
        .flatMap(fixed -> Arrays.stream(fixed.split(",", -1)))
        .map(String::strip)
        .filter(NpmSemver::isValid)
        .filter(fixed -> NpmSemver.parse(fixed).compareTo(floor) > 0)
        .min(Comparator.comparing(NpmSemver::parse))
        .orElse(null);
  }

  private static @Nullable String firstNonBlank(
      final List<KnownVulnerabilityRow> group,
      final Function<KnownVulnerabilityRow, @Nullable String> field) {

    return group.stream()
        .map(field)
        .filter(value -> value != null && !value.isBlank())
        .findFirst()
        .orElse(null);
  }

  private static @Nullable KnownVulnerabilityRow newestScan(
      final List<KnownVulnerabilityRow> group) {
    return group.stream()
        .filter(row -> row.getCompletedAt() != null)
        .max(Comparator.comparing(KnownVulnerabilityRow::getCompletedAt))
        .orElse(null);
  }
}
