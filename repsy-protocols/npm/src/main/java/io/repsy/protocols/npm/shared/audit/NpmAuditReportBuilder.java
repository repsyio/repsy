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
package io.repsy.protocols.npm.shared.audit;

import io.repsy.protocols.npm.shared.audit.NpmAuditReports.Action;
import io.repsy.protocols.npm.shared.audit.NpmAuditReports.AdvisoryMetadata;
import io.repsy.protocols.npm.shared.audit.NpmAuditReports.BulkAdvisory;
import io.repsy.protocols.npm.shared.audit.NpmAuditReports.Cvss;
import io.repsy.protocols.npm.shared.audit.NpmAuditReports.Finding;
import io.repsy.protocols.npm.shared.audit.NpmAuditReports.LegacyAdvisory;
import io.repsy.protocols.npm.shared.audit.NpmAuditReports.LegacyReport;
import io.repsy.protocols.npm.shared.audit.NpmAuditReports.Metadata;
import io.repsy.protocols.npm.shared.audit.NpmAuditReports.Person;
import io.repsy.protocols.npm.shared.audit.NpmAuditReports.Resolve;
import io.repsy.protocols.npm.shared.search.NpmSearchResult;
import io.repsy.protocols.npm.shared.utils.NpmSemver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;

/** Builds the two audit reports from the advisories that a {@link NpmAdvisorySource} found. */
@UtilityClass
@NullMarked
public class NpmAuditReportBuilder {

  /** The most paths one advisory lists, so that a huge tree cannot make a huge report. */
  static final int MAX_PATHS_PER_ADVISORY = 1000;

  private static final String VERSION_SEPARATOR = " || ";
  private static final String NO_FIX = "<0.0.0";
  private static final String FINDER = "Repsy";
  private static final String UNKNOWN_SCANNER = "repsy";

  /**
   * The bulk report: the advisories by package name, and nothing for a package without one.
   * Advisories are ordered by package name and then by id.
   */
  public static Map<String, List<BulkAdvisory>> bulk(final List<NpmAdvisory> advisories) {
    final var report = new TreeMap<String, List<BulkAdvisory>>();

    advisories.stream()
        .sorted(Comparator.comparing(NpmAdvisory::packageName).thenComparingLong(NpmAdvisory::id))
        .forEach(
            advisory ->
                report
                    .computeIfAbsent(advisory.packageName(), _ -> new ArrayList<>())
                    .add(
                        new BulkAdvisory(
                            advisory.id(),
                            advisory.url(),
                            advisory.title(),
                            advisory.severity(),
                            String.join(VERSION_SEPARATOR, advisory.vulnerableVersions()),
                            List.of(),
                            cvss(advisory))));

    return report;
  }

  /**
   * The legacy report for the tree of the request. An advisory is reported with the places of its
   * vulnerable versions in the tree, and one that matches no place is left out.
   */
  public static LegacyReport legacy(final NpmAuditTree tree, final List<NpmAdvisory> advisories) {
    final var reported = new LinkedHashMap<String, LegacyAdvisory>();
    final var actions = new ArrayList<Action>();
    final var counts = severityCounts();

    advisories.stream()
        .sorted(Comparator.comparing(NpmAdvisory::packageName).thenComparingLong(NpmAdvisory::id))
        .forEach(
            advisory -> {
              final var findings = findings(tree, advisory);

              if (findings.isEmpty()) {
                return;
              }

              reported.put(Long.toString(advisory.id()), legacyAdvisory(advisory, findings));
              actions.add(action(advisory, findings));
              counts.merge(countedSeverity(advisory.severity()), 1L, Long::sum);
            });

    return new LegacyReport(
        List.copyOf(actions),
        reported,
        List.of(),
        new Metadata(
            counts,
            tree.dependencies(),
            tree.devDependencies(),
            tree.optionalDependencies(),
            tree.totalDependencies()));
  }

  private static Cvss cvss(final NpmAdvisory advisory) {
    return new Cvss(advisory.cvssScore() == null ? 0 : advisory.cvssScore(), advisory.cvssVector());
  }

  private static Map<String, Long> severityCounts() {
    final var counts = new LinkedHashMap<String, Long>();

    for (final var severity : NpmSeverity.values()) {
      counts.put(severity.wireValue(), 0L);
    }

    return counts;
  }

  /** The {@code info} count stays 0: pnpm cannot print it, so an info advisory counts as low. */
  private static String countedSeverity(final NpmSeverity severity) {
    return severity == NpmSeverity.INFO ? NpmSeverity.LOW.wireValue() : severity.wireValue();
  }

  private static List<Finding> findings(final NpmAuditTree tree, final NpmAdvisory advisory) {
    final var vulnerable = new LinkedHashSet<>(advisory.vulnerableVersions());
    final var pathsByVersion =
        new TreeMap<String, List<NpmAuditTree.Node>>(NpmAuditReportBuilder::compareVersions);
    var listed = 0;

    for (final var node : tree.nodes()) {
      if (listed >= MAX_PATHS_PER_ADVISORY) {
        break;
      }

      if (node.name().equals(advisory.packageName()) && vulnerable.contains(node.version())) {
        pathsByVersion.computeIfAbsent(node.version(), _ -> new ArrayList<>()).add(node);
        listed++;
      }
    }

    return pathsByVersion.entrySet().stream()
        .map(
            entry -> {
              final var nodes = entry.getValue();
              final var paths = nodes.stream().map(NpmAuditTree.Node::path).distinct().toList();

              return new Finding(
                  entry.getKey(),
                  paths,
                  nodes.stream().allMatch(NpmAuditTree.Node::dev),
                  nodes.stream().allMatch(NpmAuditTree.Node::optional),
                  nodes.stream().allMatch(NpmAuditTree.Node::bundled));
            })
        .toList();
  }

  private static Action action(final NpmAdvisory advisory, final List<Finding> findings) {
    final var resolves = new LinkedHashSet<Resolve>();

    for (final var finding : findings) {
      for (final var path : finding.paths()) {
        resolves.add(
            new Resolve(advisory.id(), path, finding.dev(), finding.optional(), finding.bundled()));
      }
    }

    return new Action("review", advisory.packageName(), false, List.copyOf(resolves));
  }

  private static LegacyAdvisory legacyAdvisory(
      final NpmAdvisory advisory, final List<Finding> findings) {

    final var updated = NpmSearchResult.format(advisory.updated());

    return new LegacyAdvisory(
        findings,
        advisory.id(),
        updated,
        updated,
        false,
        advisory.title(),
        new Person(FINDER),
        new Person(advisory.reportedBy() == null ? UNKNOWN_SCANNER : advisory.reportedBy()),
        advisory.packageName(),
        advisory.cves(),
        String.join(VERSION_SEPARATOR, advisory.vulnerableVersions()),
        advisory.patchedVersions() == null ? NO_FIX : advisory.patchedVersions(),
        advisory.overview(),
        advisory.recommendation(),
        advisory.references(),
        "public",
        advisory.severity(),
        "",
        advisory.githubAdvisoryId() == null ? "" : advisory.githubAdvisoryId(),
        new AdvisoryMetadata("", 0, ""),
        advisory.url());
  }

  /** Orders semantic versions by precedence and everything else after them, as text. */
  static int compareVersions(final String a, final String b) {
    final var aValid = NpmSemver.isValid(a);
    final var bValid = NpmSemver.isValid(b);

    if (aValid && bValid) {
      final var byPrecedence = NpmSemver.parse(a).compareTo(NpmSemver.parse(b));
      return byPrecedence != 0 ? byPrecedence : a.compareTo(b);
    }

    if (aValid != bValid) {
      return aValid ? -1 : 1;
    }

    return a.compareTo(b);
  }
}
