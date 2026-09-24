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
package io.repsy.os.server.protocols.nuget.shared.packages.mappers;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackage;
import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackageVersion;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetPackageSearchResult;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetPackageSearchResult.VersionSummary;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("NuGetPackageConverter.toSearchResult")
class NuGetPackageConverterTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private final NuGetPackageConverter converter = new NuGetPackageConverterImpl();
  private final NuGetPackage pkg = pkg();

  private static NuGetPackage pkg() {
    final var pkg = new NuGetPackage();
    pkg.setPackageId("Some.Package");
    return pkg;
  }

  /** A version row published {@code daysAfterStart} days after the first one. */
  private static NuGetPackageVersion version(
      final String version, final long daysAfterStart, final long downloads) {
    final var row = new NuGetPackageVersion();
    row.setVersion(version);
    row.setPrerelease(version.contains("-"));
    row.setListed(true);
    row.setPublishedAt(T0.plusSeconds(daysAfterStart * 86_400));
    row.setDownloadCount(downloads);
    row.setTitle("Title of " + version);
    return row;
  }

  private static List<String> versionsOf(final NuGetPackageSearchResult result) {
    return result.versions().stream().map(VersionSummary::version).toList();
  }

  @Test
  @DisplayName("reports the highest version as the latest, not the most recently published one")
  void backportPublishedAfterANewerRelease() {
    // The repository hands the versions over newest published first.
    final var backport = version("1.0.5", 3, 5);
    final var newer = version("2.0.0", 2, 20);
    final var older = version("1.0.4", 1, 1);

    final var result =
        this.converter.toSearchResult(this.pkg, false, true, List.of(backport, newer, older));

    assertThat(result.latestVersion()).isEqualTo("2.0.0");
    assertThat(result.title()).isEqualTo("Title of 2.0.0");
    assertThat(versionsOf(result)).containsExactly("2.0.0", "1.0.5", "1.0.4");
    assertThat(result.versions().getFirst().version()).isEqualTo(result.latestVersion());
    assertThat(result.totalDownloads()).isEqualTo(26);
  }

  @Test
  @DisplayName("compares the parts numerically, not as text")
  void numericOrder() {
    final var result =
        this.converter.toSearchResult(
            this.pkg,
            false,
            true,
            List.of(version("1.9.0", 3, 0), version("1.10.0", 1, 0), version("1.2.0", 2, 0)));

    assertThat(result.latestVersion()).isEqualTo("1.10.0");
    assertThat(versionsOf(result)).containsExactly("1.10.0", "1.9.0", "1.2.0");
  }

  @Test
  @DisplayName("orders by the fourth part when the first three are equal")
  void fourthPart() {
    final var result =
        this.converter.toSearchResult(
            this.pkg,
            false,
            true,
            List.of(version("1.0.0.10", 1, 0), version("1.0.0.9", 2, 0), version("1.0.0", 3, 0)));

    assertThat(result.latestVersion()).isEqualTo("1.0.0.10");
    assertThat(versionsOf(result)).containsExactly("1.0.0.10", "1.0.0.9", "1.0.0");
  }

  @Test
  @DisplayName("leaves pre-releases out unless they are asked for")
  void preReleaseExcludedByDefault() {
    final var result =
        this.converter.toSearchResult(
            this.pkg,
            false,
            true,
            List.of(version("2.0.0-beta", 3, 100), version("1.0.0", 2, 1), version("1.1.0", 1, 2)));

    assertThat(result.latestVersion()).isEqualTo("1.1.0");
    assertThat(versionsOf(result)).containsExactly("1.1.0", "1.0.0");
    assertThat(result.totalDownloads()).isEqualTo(3);
  }

  @Test
  @DisplayName("reports a pre-release as the latest when it is asked for and it is the highest")
  void preReleaseIncludedWhenRequested() {
    final var result =
        this.converter.toSearchResult(
            this.pkg,
            true,
            true,
            List.of(version("1.1.0", 3, 2), version("2.0.0-beta", 1, 100), version("1.0.0", 2, 1)));

    assertThat(result.latestVersion()).isEqualTo("2.0.0-beta");
    assertThat(versionsOf(result)).containsExactly("2.0.0-beta", "1.1.0", "1.0.0");
    assertThat(result.totalDownloads()).isEqualTo(103);
  }

  @Test
  @DisplayName("sorts a release above its own pre-releases and pre-releases by their label")
  void preReleaseOrderWithinOneVersion() {
    final var result =
        this.converter.toSearchResult(
            this.pkg,
            true,
            true,
            List.of(
                version("2.0.0-alpha", 4, 0),
                version("2.0.0", 1, 0),
                version("2.0.0-beta.2", 2, 0),
                version("2.0.0-beta.10", 3, 0)));

    assertThat(result.latestVersion()).isEqualTo("2.0.0");
    assertThat(versionsOf(result))
        .containsExactly("2.0.0", "2.0.0-beta.10", "2.0.0-beta.2", "2.0.0-alpha");
  }

  @Test
  @DisplayName("falls back to the highest pre-release when there is nothing else")
  void onlyPreReleases() {
    // 1.0.0-rc is the newest published, 2.0.0-beta the highest.
    final var result =
        this.converter.toSearchResult(
            this.pkg,
            false,
            true,
            List.of(
                version("1.0.0-rc", 3, 1),
                version("2.0.0-beta", 1, 2),
                version("1.0.0-beta", 2, 3)));

    assertThat(result.latestVersion()).isEqualTo("2.0.0-beta");
    assertThat(result.title()).isEqualTo("Title of 2.0.0-beta");
    assertThat(result.versions()).isEmpty();
    assertThat(result.totalDownloads()).isZero();
  }

  @Test
  @DisplayName("answers an empty result for a package without listed versions")
  void noVersions() {
    final var result = this.converter.toSearchResult(this.pkg, true, true, List.of());

    assertThat(result.packageId()).isEqualTo("Some.Package");
    assertThat(result.latestVersion()).isEmpty();
    assertThat(result.versions()).isEmpty();
    assertThat(result.totalDownloads()).isZero();
  }

  @Test
  @DisplayName("does not depend on the order the versions arrive in")
  void inputOrderDoesNotMatter() {
    final var first = version("1.0.0", 1, 0);
    final var second = version("3.0.0", 2, 0);
    final var third = version("2.0.0", 3, 0);

    assertThat(this.converter.toSearchResult(this.pkg, false, true, List.of(first, second, third)))
        .isEqualTo(
            this.converter.toSearchResult(this.pkg, false, true, List.of(third, first, second)));
  }

  @Test
  @DisplayName(
      "leaves SemVer 2.0.0-only versions out unless the client opted in to them (RPS-1275)")
  void semVer2VersionsNeedAnOptIn() {
    final var versions =
        List.of(
            version("2.0.0-beta.1", 4, 40),
            version("1.1.0+build.5", 3, 30),
            version("1.0.1-beta2", 2, 20),
            version("1.0.0", 1, 10));

    final var without = this.converter.toSearchResult(this.pkg, true, false, versions);
    final var with = this.converter.toSearchResult(this.pkg, true, true, versions);

    assertThat(without.latestVersion()).isEqualTo("1.0.1-beta2");
    assertThat(versionsOf(without)).containsExactly("1.0.1-beta2", "1.0.0");
    assertThat(without.totalDownloads()).isEqualTo(30);
    assertThat(with.latestVersion()).isEqualTo("2.0.0-beta.1");
    assertThat(versionsOf(with))
        .containsExactly("2.0.0-beta.1", "1.1.0+build.5", "1.0.1-beta2", "1.0.0");
    assertThat(with.totalDownloads()).isEqualTo(100);
  }

  @Test
  @DisplayName("answers an empty result when a package only has SemVer 2.0.0-only versions")
  void onlySemVer2Versions() {
    final var result =
        this.converter.toSearchResult(
            this.pkg, true, false, List.of(version("1.0.0-rc.1", 1, 1), version("1.0.0+b", 2, 2)));

    assertThat(result.latestVersion()).isEmpty();
    assertThat(result.versions()).isEmpty();
  }
}
