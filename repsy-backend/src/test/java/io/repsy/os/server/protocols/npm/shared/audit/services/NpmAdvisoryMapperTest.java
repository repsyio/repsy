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

import io.repsy.os.server.protocols.npm.shared.audit.services.NpmAdvisoryMapper.Key;
import io.repsy.os.server.security.scan.dtos.FixStatus;
import io.repsy.os.server.security.scan.dtos.KnownVulnerabilityRow;
import io.repsy.os.server.security.scan.dtos.Severity;
import io.repsy.protocols.npm.shared.audit.NpmAdvisory;
import io.repsy.protocols.npm.shared.audit.NpmSeverity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("NpmAdvisoryMapper")
class NpmAdvisoryMapperTest {

  private static final Instant T1 = Instant.parse("2026-09-24T10:00:00Z");
  private static final Instant T2 = Instant.parse("2026-09-25T10:00:00Z");

  private record Row(
      String cveId,
      Severity severity,
      String packageName,
      String packageVersion,
      String fixedVersion,
      String description,
      String referenceUrl,
      Double cvssScore,
      String cvssVector,
      Instant completedAt,
      String scannerName)
      implements KnownVulnerabilityRow {

    @Override
    public String getCveId() {
      return this.cveId;
    }

    @Override
    public Severity getSeverity() {
      return this.severity;
    }

    @Override
    public String getPackageName() {
      return this.packageName;
    }

    @Override
    public String getPackageVersion() {
      return this.packageVersion;
    }

    @Override
    public String getFixedVersion() {
      return this.fixedVersion;
    }

    @Override
    public String getDescription() {
      return this.description;
    }

    @Override
    public String getReferenceUrl() {
      return this.referenceUrl;
    }

    @Override
    public FixStatus getFixStatus() {
      return FixStatus.FIXED;
    }

    @Override
    public Double getCvssScore() {
      return this.cvssScore;
    }

    @Override
    public String getCvssVector() {
      return this.cvssVector;
    }

    @Override
    public Instant getCompletedAt() {
      return this.completedAt;
    }

    @Override
    public String getScannerName() {
      return this.scannerName;
    }
  }

  private static Row row(
      final String cve, final String name, final String version, final String fixed) {
    return new Row(
        cve,
        Severity.HIGH,
        name,
        version,
        fixed,
        "desc",
        "https://ref/" + cve,
        7.5,
        "CVSS:3.1/x",
        T1,
        "trivy");
  }

  private static List<NpmAdvisory> map(
      final List<KnownVulnerabilityRow> rows, final String name, final String... versions) {
    return NpmAdvisoryMapper.toAdvisories(rows, Map.of(name, Set.of(versions)));
  }

  @ParameterizedTest
  @CsvSource({"CRITICAL, CRITICAL", "HIGH, HIGH", "MEDIUM, MODERATE", "LOW, LOW", "UNKNOWN, LOW"})
  @DisplayName("maps Trivy severities to npm severities, and an unknown one to low, never info")
  void severities(final Severity trivy, final NpmSeverity npm) {
    assertThat(NpmAdvisoryMapper.toNpmSeverity(trivy)).isEqualTo(npm);
  }

  @Test
  @DisplayName("refuses a missing severity")
  void missingSeverity() {
    assertThatThrownBy(() -> NpmAdvisoryMapper.toNpmSeverity(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("takes the worst severity of the findings of one vulnerability")
  void worstSeverity() {
    final var low =
        new Row("CVE-1", Severity.LOW, "a", "1.0.0", null, null, null, null, null, T1, null);
    final var medium =
        new Row("CVE-1", Severity.MEDIUM, "a", "1.1.0", null, null, null, null, null, T1, null);

    assertThat(map(List.of(low, medium), "a", "1.0.0", "1.1.0").getFirst().severity())
        .isEqualTo(NpmSeverity.MODERATE);
  }

  @Test
  @DisplayName("builds an advisory from a finding")
  void buildsAnAdvisory() {
    final var advisories =
        map(List.of(row("CVE-2021-23337", "lodash", "4.17.20", "4.17.21")), "lodash", "4.17.20");

    assertThat(advisories).hasSize(1);
    final var advisory = advisories.getFirst();
    assertThat(advisory.packageName()).isEqualTo("lodash");
    assertThat(advisory.title()).isEqualTo("CVE-2021-23337: desc");
    assertThat(advisory.url()).isEqualTo("https://ref/CVE-2021-23337");
    assertThat(advisory.severity()).isEqualTo(NpmSeverity.HIGH);
    assertThat(advisory.vulnerableVersions()).containsExactly("4.17.20");
    assertThat(advisory.patchedVersions()).isEqualTo(">=4.17.21");
    assertThat(advisory.recommendation()).isEqualTo("Upgrade to version 4.17.21 or later.");
    assertThat(advisory.cves()).containsExactly("CVE-2021-23337");
    assertThat(advisory.githubAdvisoryId()).isNull();
    assertThat(advisory.overview()).isEqualTo("desc");
    assertThat(advisory.references()).isEqualTo("https://ref/CVE-2021-23337");
    assertThat(advisory.cvssScore()).isEqualTo(7.5);
    assertThat(advisory.cvssVector()).isEqualTo("CVSS:3.1/x");
    assertThat(advisory.updated()).isEqualTo(T1);
    assertThat(advisory.reportedBy()).isEqualTo("trivy");
  }

  @Test
  @DisplayName("lists the requested vulnerable versions, oldest first, without duplicates")
  void vulnerableVersionsAreTheRequestedOnes() {
    final var rows =
        List.<KnownVulnerabilityRow>of(
            row("CVE-1", "a", "1.10.0", null),
            row("CVE-1", "a", "1.2.0", null),
            row("CVE-1", "a", "1.2.0", null),
            row("CVE-1", "a", "latest", null));

    assertThat(map(rows, "a", "1.10.0", "1.2.0", "latest").getFirst().vulnerableVersions())
        .containsExactly("1.2.0", "1.10.0");
    assertThat(map(rows, "a", "1.2.0").getFirst().vulnerableVersions()).containsExactly("1.2.0");
  }

  @Test
  @DisplayName("computes everything from the requested versions: 2.0.0 alone is patched in 2.0.3")
  void restrictsToTheRequestedVersionsBeforeComputing() {
    final var rows =
        List.<KnownVulnerabilityRow>of(
            new Row(
                "CVE-9",
                Severity.CRITICAL,
                "a",
                "1.0.0",
                "1.0.5",
                "one",
                null,
                9.8,
                "v1",
                T1,
                "s1"),
            new Row(
                "CVE-9", Severity.LOW, "a", "2.0.0", "2.0.3", "two", null, 3.0, "v2", T2, "s2"));

    final var only2 = map(rows, "a", "2.0.0");
    final var only1 = map(rows, "a", "1.0.0");
    final var both = map(rows, "a", "1.0.0", "2.0.0");

    assertThat(only2).hasSize(1);
    assertThat(only2.getFirst().vulnerableVersions()).containsExactly("2.0.0");
    assertThat(only2.getFirst().patchedVersions()).isEqualTo(">=2.0.3");
    assertThat(only2.getFirst().severity()).isEqualTo(NpmSeverity.LOW);
    assertThat(only2.getFirst().cvssScore()).isEqualTo(3.0);
    assertThat(only2.getFirst().overview()).isEqualTo("two");
    assertThat(only2.getFirst().reportedBy()).isEqualTo("s2");
    assertThat(only1.getFirst().vulnerableVersions()).containsExactly("1.0.0");
    assertThat(only1.getFirst().patchedVersions()).isEqualTo(">=1.0.5");
    assertThat(only1.getFirst().severity()).isEqualTo(NpmSeverity.CRITICAL);
    assertThat(both.getFirst().vulnerableVersions()).containsExactly("1.0.0", "2.0.0");
    assertThat(both.getFirst().patchedVersions()).isEqualTo(">=1.0.5");
  }

  @Test
  @DisplayName(
      "leaves out a vulnerability when none of the requested versions is among its versions")
  void requestedVersionMustBeVulnerable() {
    final var rows = List.<KnownVulnerabilityRow>of(row("CVE-1", "a", "1.0.0", null));

    assertThat(map(rows, "a", "2.0.0")).isEmpty();
    assertThat(map(rows, "other", "1.0.0")).isEmpty();
    assertThat(NpmAdvisoryMapper.toAdvisories(rows, Map.of())).isEmpty();
  }

  @Test
  @DisplayName("leaves out a vulnerability found only in versions that are not semver")
  void dropsInvalidVersions() {
    assertThat(map(List.of(row("CVE-1", "a", "1.0", null)), "a", "1.0")).isEmpty();
  }

  @Test
  @DisplayName("makes one advisory for each vulnerability and package")
  void oneAdvisoryPerVulnerabilityAndPackage() {
    final var rows =
        List.<KnownVulnerabilityRow>of(
            row("CVE-1", "a", "1.0.0", null),
            row("CVE-2", "a", "1.0.0", null),
            row("CVE-1", "b", "1.0.0", null));

    final var advisories =
        NpmAdvisoryMapper.toAdvisories(rows, Map.of("a", Set.of("1.0.0"), "b", Set.of("1.0.0")));

    assertThat(advisories).hasSize(3);
    assertThat(advisories).extracting(NpmAdvisory::id).doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("patched version is the smallest fix above the smallest vulnerable version")
  void patchedVersions() {
    final var many = row("CVE-1", "a", "2.1.0", "1.9.0, 3.0.1, 2.5.7, 2.5.0-beta.1, junk");
    final var none = row("CVE-2", "a", "2.1.0", null);
    final var blank = row("CVE-3", "a", "2.1.0", " ");
    final var below = row("CVE-4", "a", "2.1.0", "1.0.0");

    final var advisories =
        NpmAdvisoryMapper.toAdvisories(
            List.of(many, none, blank, below), Map.of("a", Set.of("2.1.0")));

    assertThat(advisories)
        .extracting(NpmAdvisory::patchedVersions)
        .containsExactly(">=2.5.0-beta.1", null, null, null);
    assertThat(advisories.get(1).recommendation()).isEqualTo("No fix is available yet.");
  }

  @Test
  @DisplayName("id is stable, distinct for another vulnerability or package, and a safe integer")
  void ids() {
    final var id = NpmAdvisoryMapper.id(new Key("CVE-2021-23337", "lodash"));

    assertThat(NpmAdvisoryMapper.id(new Key("CVE-2021-23337", "lodash"))).isEqualTo(id);
    assertThat(NpmAdvisoryMapper.id(new Key("CVE-2021-23337", "other"))).isNotEqualTo(id);
    assertThat(NpmAdvisoryMapper.id(new Key("CVE-2021-23338", "lodash"))).isNotEqualTo(id);
    assertThat(id).isPositive().isLessThanOrEqualTo((1L << 53) - 1);
  }

  @Test
  @DisplayName("url falls back to the NVD for a CVE and to GitHub for an advisory")
  void urls() {
    assertThat(NpmAdvisoryMapper.url("CVE-1", "https://own")).isEqualTo("https://own");
    assertThat(NpmAdvisoryMapper.url("CVE-1", null))
        .isEqualTo("https://nvd.nist.gov/vuln/detail/CVE-1");
    assertThat(NpmAdvisoryMapper.url("GHSA-a", null))
        .isEqualTo("https://github.com/advisories/GHSA-a");
    assertThat(NpmAdvisoryMapper.url("OSV-1", null)).isEmpty();
  }

  @Test
  @DisplayName("takes a blank reference as none")
  void blankReference() {
    final var blank =
        new Row("CVE-1", Severity.LOW, "a", "1.0.0", null, "d", " ", null, null, T1, null);

    final var advisory = map(List.of(blank), "a", "1.0.0").getFirst();

    assertThat(advisory.url()).isEqualTo("https://nvd.nist.gov/vuln/detail/CVE-1");
    assertThat(advisory.references()).isEmpty();
  }

  @Test
  @DisplayName("title is the id and the first line of the description, cut at 150 characters")
  void titles() {
    assertThat(NpmAdvisoryMapper.title("CVE-1", null)).isEqualTo("CVE-1");
    assertThat(NpmAdvisoryMapper.title("CVE-1", "  Lodash   is\tbad.\nSecond line"))
        .isEqualTo("CVE-1: Lodash is bad.");

    final var title = NpmAdvisoryMapper.title("CVE-1", "x".repeat(400));

    assertThat(title).isEqualTo("CVE-1: " + "x".repeat(147) + "...");
    assertThat(title.length() - "CVE-1: ".length()).isEqualTo(NpmAdvisoryMapper.MAX_TITLE_LENGTH);
  }

  @Test
  @DisplayName("names a CVE in cves and a GitHub advisory in its own field")
  void identifiers() {
    final var rows =
        List.<KnownVulnerabilityRow>of(
            row("CVE-1", "a", "1.0.0", null), row("GHSA-x", "a", "1.0.0", null));

    final var advisories = map(rows, "a", "1.0.0");

    assertThat(advisories.get(0).cves()).containsExactly("CVE-1");
    assertThat(advisories.get(0).githubAdvisoryId()).isNull();
    assertThat(advisories.get(1).cves()).isEmpty();
    assertThat(advisories.get(1).githubAdvisoryId()).isEqualTo("GHSA-x");
  }

  @Test
  @DisplayName("takes the CVSS of the finding with the highest score and the newest scan's facts")
  void cvssAndScan() {
    final var lowScore =
        new Row(
            "CVE-1", Severity.HIGH, "a", "1.0.0", null, null, null, 4.0, "low", T1, "old-scanner");
    final var highScore =
        new Row(
            "CVE-1", Severity.HIGH, "a", "1.1.0", null, null, null, 9.8, "high", T2, "new-scanner");

    final var advisory = map(List.of(lowScore, highScore), "a", "1.0.0", "1.1.0").getFirst();

    assertThat(advisory.cvssScore()).isEqualTo(9.8);
    assertThat(advisory.cvssVector()).isEqualTo("high");
    assertThat(advisory.updated()).isEqualTo(T2);
    assertThat(advisory.reportedBy()).isEqualTo("new-scanner");
  }

  @Test
  @DisplayName("has no CVSS and an epoch time when the scan says nothing")
  void nothingKnown() {
    final var bare =
        new Row("CVE-1", Severity.LOW, "a", "1.0.0", null, null, null, null, null, null, null);

    final var advisory = map(List.of(bare), "a", "1.0.0").getFirst();

    assertThat(advisory.cvssScore()).isNull();
    assertThat(advisory.cvssVector()).isNull();
    assertThat(advisory.updated()).isEqualTo(Instant.EPOCH);
    assertThat(advisory.reportedBy()).isNull();
    assertThat(advisory.overview()).isEmpty();
    assertThat(advisory.title()).isEqualTo("CVE-1");
  }
}
