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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("NpmAuditReportBuilder")
class NpmAuditReportBuilderTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Instant UPDATED = Instant.parse("2026-09-24T10:00:00.5Z");

  private static NpmAdvisory advisory(
      final long id,
      final String name,
      final NpmSeverity severity,
      final String patched,
      final String... vulnerable) {
    return new NpmAdvisory(
        id,
        name,
        "CVE-1: " + name,
        "https://example.com/" + id,
        severity,
        List.of(vulnerable),
        patched,
        List.of("CVE-1"),
        null,
        "overview " + id,
        "Upgrade to version 9.9.9 or later.",
        "https://ref/" + id,
        7.2,
        "CVSS:3.1/AV:N",
        UPDATED,
        "trivy");
  }

  private static NpmAuditTree tree(final String json) {
    return NpmAuditTree.parse(MAPPER.readTree(json));
  }

  private static JsonNode json(final Object value) {
    return MAPPER.readTree(MAPPER.writeValueAsString(value));
  }

  @Test
  @DisplayName("bulk answers the advisories by package, with the shape arborist and yarn read")
  void bulkShape() {
    final var report =
        NpmAuditReportBuilder.bulk(
            List.of(
                advisory(2, "lodash", NpmSeverity.HIGH, ">=4.17.21", "4.17.19", "4.17.20"),
                advisory(1, "lodash", NpmSeverity.LOW, null, "4.17.20"),
                advisory(3, "acorn", NpmSeverity.MODERATE, null, "1.0.0")));

    final var json = json(report);

    assertThat(json.propertyNames()).containsExactly("acorn", "lodash");
    assertThat(json.get("lodash")).hasSize(2);
    assertThat(json.get("lodash").get(0).get("id").asLong()).isEqualTo(1);
    final var high = json.get("lodash").get(1);
    assertThat(high.get("id").asLong()).isEqualTo(2);
    assertThat(high.get("url").asString()).isEqualTo("https://example.com/2");
    assertThat(high.get("title").asString()).isEqualTo("CVE-1: lodash");
    assertThat(high.get("severity").asString()).isEqualTo("high");
    assertThat(high.get("vulnerable_versions").asString()).isEqualTo("4.17.19 || 4.17.20");
    assertThat(high.get("cwe").isArray()).isTrue();
    assertThat(high.get("cvss").get("score").asDouble()).isEqualTo(7.2);
    assertThat(high.get("cvss").get("vectorString").asString()).isEqualTo("CVSS:3.1/AV:N");
    assertThat(json.get("acorn").get(0).get("severity").asString()).isEqualTo("moderate");
  }

  @Test
  @DisplayName("bulk sends a score of 0 and no vector when the CVSS is unknown")
  void bulkWithoutCvss() {
    final var unknown =
        new NpmAdvisory(
            1,
            "a",
            "t",
            "u",
            NpmSeverity.LOW,
            List.of("1.0.0"),
            null,
            List.of(),
            null,
            "",
            "",
            "",
            null,
            null,
            UPDATED,
            null);

    final var cvss = json(NpmAuditReportBuilder.bulk(List.of(unknown))).get("a").get(0).get("cvss");

    assertThat(cvss.get("score").asDouble()).isZero();
    assertThat(cvss.has("vectorString")).isFalse();
  }

  @Test
  @DisplayName("bulk is an empty object when nothing is known")
  void bulkEmpty() {
    assertThat(NpmAuditReportBuilder.bulk(List.of())).isEmpty();
  }

  @Test
  @DisplayName("legacy reports the places of the vulnerable version, with actions and counts")
  void legacyShape() {
    final var tree =
        tree(
            """
            {"dependencies":{
              "a":{"version":"1.0.0","dependencies":{"lodash":{"version":"4.17.20"}}},
              "b":{"version":"1.0.0","dev":true,"dependencies":{"lodash":{"version":"4.17.20","dev":true}}},
              "lodash":{"version":"4.17.21"}}}
            """);
    final var report =
        NpmAuditReportBuilder.legacy(
            tree, List.of(advisory(5, "lodash", NpmSeverity.HIGH, ">=4.17.21", "4.17.20")));

    final var json = json(report);
    final var advisory = json.get("advisories").get("5");

    assertThat(advisory.get("id").asLong()).isEqualTo(5);
    assertThat(advisory.get("module_name").asString()).isEqualTo("lodash");
    assertThat(advisory.get("severity").asString()).isEqualTo("high");
    assertThat(advisory.get("vulnerable_versions").asString()).isEqualTo("4.17.20");
    assertThat(advisory.get("patched_versions").asString()).isEqualTo(">=4.17.21");
    assertThat(advisory.get("cves").get(0).asString()).isEqualTo("CVE-1");
    assertThat(advisory.get("github_advisory_id").asString()).isEmpty();
    assertThat(advisory.get("title").asString()).isEqualTo("CVE-1: lodash");
    assertThat(advisory.get("url").asString()).isEqualTo("https://example.com/5");
    assertThat(advisory.get("overview").asString()).isEqualTo("overview 5");
    assertThat(advisory.get("recommendation").asString()).contains("9.9.9");
    assertThat(advisory.get("references").asString()).isEqualTo("https://ref/5");
    assertThat(advisory.get("access").asString()).isEqualTo("public");
    assertThat(advisory.get("cwe").asString()).isEmpty();
    assertThat(advisory.get("deleted").asBoolean()).isFalse();
    assertThat(advisory.get("created").asString()).isEqualTo("2026-09-24T10:00:00.500Z");
    assertThat(advisory.get("updated").asString()).isEqualTo("2026-09-24T10:00:00.500Z");
    assertThat(advisory.get("found_by").get("name").asString()).isEqualTo("Repsy");
    assertThat(advisory.get("reported_by").get("name").asString()).isEqualTo("trivy");
    assertThat(advisory.get("metadata").get("exploitability").asInt()).isZero();

    final var finding = advisory.get("findings").get(0);
    assertThat(finding.get("version").asString()).isEqualTo("4.17.20");
    assertThat(finding.get("paths"))
        .extracting(JsonNode::asString)
        .containsExactly("a>lodash", "b>lodash");
    assertThat(finding.get("dev").asBoolean()).isFalse();
    assertThat(finding.get("optional").asBoolean()).isFalse();
    assertThat(finding.get("bundled").asBoolean()).isFalse();

    final var action = json.get("actions").get(0);
    assertThat(action.get("action").asString()).isEqualTo("review");
    assertThat(action.get("module").asString()).isEqualTo("lodash");
    assertThat(action.get("isMajor").asBoolean()).isFalse();
    assertThat(action.get("resolves")).hasSize(2);
    assertThat(action.get("resolves").get(0).get("id").asLong()).isEqualTo(5);
    assertThat(action.get("resolves").get(0).get("path").asString()).isEqualTo("a>lodash");
    assertThat(json.get("muted")).isEmpty();

    final var metadata = json.get("metadata");
    assertThat(metadata.get("vulnerabilities").get("high").asInt()).isEqualTo(1);
    assertThat(metadata.get("dependencies").asInt()).isEqualTo(3);
    assertThat(metadata.get("devDependencies").asInt()).isEqualTo(2);
    assertThat(metadata.get("optionalDependencies").asInt()).isZero();
    assertThat(metadata.get("totalDependencies").asInt()).isEqualTo(5);
  }

  @Test
  @DisplayName("legacy flags a finding dev or optional only when every one of its paths is")
  void legacyFlagsNeedEveryPath() {
    final var tree =
        tree(
            """
            {"dependencies":{
              "a":{"version":"1.0.0","dev":true,"optional":true,"bundled":true},
              "b":{"version":"1.0.0","dev":true,"dependencies":{"a":{"version":"1.0.0","dev":true,"optional":true,"bundled":true}}},
              "c":{"version":"2.0.0","dependencies":{"a":{"version":"1.0.0","dev":true}}}}}
            """);

    final var allFlagged =
        json(NpmAuditReportBuilder.legacy(
                tree, List.of(advisory(1, "a", NpmSeverity.LOW, null, "1.0.0"))))
            .get("advisories")
            .get("1")
            .get("findings")
            .get(0);

    // Three occurrences of a@1.0.0: the third is only dev, so nothing is optional or bundled.
    assertThat(allFlagged.get("dev").asBoolean()).isTrue();
    assertThat(allFlagged.get("optional").asBoolean()).isFalse();
    assertThat(allFlagged.get("bundled").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("legacy has one finding for each vulnerable version, oldest first")
  void legacyFindingsByVersion() {
    final var tree =
        tree(
            "{\"dependencies\":{\"x\":{\"version\":\"1.10.0\"},\"y\":{\"version\":\"1.2.0\","
                + "\"dependencies\":{\"x\":{\"version\":\"1.2.0\"}}},\"z\":{\"version\":\"3.0.0\"}}}");

    final var findings =
        json(NpmAuditReportBuilder.legacy(
                tree, List.of(advisory(1, "x", NpmSeverity.LOW, null, "1.10.0", "1.2.0", "9.9.9"))))
            .get("advisories")
            .get("1")
            .get("findings");

    assertThat(findings).hasSize(2);
    assertThat(findings.get(0).get("version").asString()).isEqualTo("1.2.0");
    assertThat(findings.get(1).get("version").asString()).isEqualTo("1.10.0");
  }

  @Test
  @DisplayName("legacy says no fix with <0.0.0, and takes the GitHub id and a missing scanner")
  void legacyNoFix() {
    final var noFix =
        new NpmAdvisory(
            9,
            "a",
            "t",
            "u",
            NpmSeverity.CRITICAL,
            List.of("1.0.0"),
            null,
            List.of(),
            "GHSA-xxxx",
            "",
            "",
            "",
            null,
            null,
            UPDATED,
            null);
    final var tree = tree("{\"dependencies\":{\"a\":{\"version\":\"1.0.0\"}}}");

    final var advisory =
        json(NpmAuditReportBuilder.legacy(tree, List.of(noFix))).get("advisories").get("9");

    assertThat(advisory.get("patched_versions").asString()).isEqualTo("<0.0.0");
    assertThat(advisory.get("github_advisory_id").asString()).isEqualTo("GHSA-xxxx");
    assertThat(advisory.get("reported_by").get("name").asString()).isEqualTo("repsy");
  }

  @Test
  @DisplayName("legacy counts advisories by severity, with info always 0")
  void legacyCountsAdvisories() {
    final var tree =
        tree(
            "{\"dependencies\":{\"a\":{\"version\":\"1.0.0\"},\"b\":{\"version\":\"1.0.0\"},\"c\":{\"version\":\"1.0.0\"},"
                + "\"d\":{\"version\":\"1.0.0\"},\"e\":{\"version\":\"1.0.0\"}}}");

    final var counts =
        json(NpmAuditReportBuilder.legacy(
                tree,
                List.of(
                    advisory(1, "a", NpmSeverity.CRITICAL, null, "1.0.0"),
                    advisory(2, "b", NpmSeverity.HIGH, null, "1.0.0"),
                    advisory(3, "c", NpmSeverity.HIGH, null, "1.0.0"),
                    advisory(4, "d", NpmSeverity.MODERATE, null, "1.0.0"),
                    advisory(5, "e", NpmSeverity.INFO, null, "1.0.0"))))
            .get("metadata")
            .get("vulnerabilities");

    assertThat(counts.get("critical").asInt()).isEqualTo(1);
    assertThat(counts.get("high").asInt()).isEqualTo(2);
    assertThat(counts.get("moderate").asInt()).isEqualTo(1);
    assertThat(counts.get("low").asInt()).isEqualTo(1);
    assertThat(counts.get("info").asInt()).isZero();
  }

  @Test
  @DisplayName("legacy leaves out an advisory that matches nothing in the tree")
  void legacyLeavesOutUnmatched() {
    final var tree = tree("{\"dependencies\":{\"a\":{\"version\":\"1.0.0\"}}}");

    final var json =
        json(
            NpmAuditReportBuilder.legacy(
                tree,
                List.of(
                    advisory(1, "a", NpmSeverity.LOW, null, "2.0.0"),
                    advisory(2, "other", NpmSeverity.LOW, null, "1.0.0"))));

    assertThat(json.get("advisories")).isEmpty();
    assertThat(json.get("actions")).isEmpty();
  }

  @Test
  @DisplayName("legacy of nothing is a report with all five counts and no advisories")
  void legacyEmpty() {
    final var json = json(NpmAuditReportBuilder.legacy(tree("{}"), List.of()));

    assertThat(json.get("advisories").isObject()).isTrue();
    assertThat(json.get("advisories")).isEmpty();
    assertThat(json.get("actions").isArray()).isTrue();
    assertThat(json.get("muted").isArray()).isTrue();
    assertThat(json.get("metadata").get("vulnerabilities").propertyNames())
        .containsExactly("info", "low", "moderate", "high", "critical");
    assertThat(json.get("metadata").get("totalDependencies").asInt()).isZero();
  }

  @Test
  @DisplayName("legacy lists at most 1000 paths for an advisory")
  void legacyCapsThePaths() {
    final var packages = new StringBuilder("{\"dependencies\":{");
    for (var i = 0; i < NpmAuditReportBuilder.MAX_PATHS_PER_ADVISORY + 50; i++) {
      packages
          .append(i == 0 ? "" : ",")
          .append("\"p")
          .append(i)
          .append("\":{\"version\":\"1.0.0\",\"dependencies\":{\"a\":{\"version\":\"1.0.0\"}}}");
    }
    packages.append("}}");

    final var json =
        json(
            NpmAuditReportBuilder.legacy(
                tree(packages.toString()),
                List.of(advisory(1, "a", NpmSeverity.LOW, null, "1.0.0"))));

    assertThat(json.get("advisories").get("1").get("findings").get(0).get("paths"))
        .hasSize(NpmAuditReportBuilder.MAX_PATHS_PER_ADVISORY);
    assertThat(json.get("actions").get(0).get("resolves"))
        .hasSize(NpmAuditReportBuilder.MAX_PATHS_PER_ADVISORY);
  }

  @Test
  @DisplayName("orders versions by precedence, and text that is not a version after them")
  void compareVersions() {
    assertThat(NpmAuditReportBuilder.compareVersions("1.2.0", "1.10.0")).isNegative();
    assertThat(NpmAuditReportBuilder.compareVersions("1.10.0", "1.2.0")).isPositive();
    assertThat(NpmAuditReportBuilder.compareVersions("1.0.0+a", "1.0.0+b")).isNegative();
    assertThat(NpmAuditReportBuilder.compareVersions("1.0.0", "latest")).isNegative();
    assertThat(NpmAuditReportBuilder.compareVersions("latest", "1.0.0")).isPositive();
    assertThat(NpmAuditReportBuilder.compareVersions("a", "b")).isNegative();
  }

  @Test
  @DisplayName("severities are written in lowercase")
  void severityWire() {
    assertThat(NpmSeverity.MODERATE.wireValue()).isEqualTo("moderate");
    assertThat(json(NpmSeverity.CRITICAL).asString()).isEqualTo("critical");
  }
}
