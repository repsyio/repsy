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
package io.repsy.protocols.npm.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.protocols.npm.shared.npm_package.dtos.NpmPackageSnapshot;
import io.repsy.protocols.npm.shared.npm_package.dtos.NpmPackageSnapshot.Maintainer;
import io.repsy.protocols.npm.shared.npm_package.dtos.NpmPackageSnapshot.Version;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RPS-1300: {@link NpmPackumentBuilder} rebuilds a package's metadata from its rows, takes the rest
 * from the tarball where there is one, and invents nothing.
 */
@DisplayName("NpmPackumentBuilder (RPS-1300)")
@SuppressWarnings("unchecked")
class NpmPackumentBuilderTest {

  private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05.678Z");
  private static final Instant PUBLISHED = Instant.parse("2026-02-03T04:05:06.789Z");
  private static final Instant MODIFIED = Instant.parse("2026-09-24T10:11:12.131Z");

  private static Version version(final String version) {
    return new Version(
        version, PUBLISHED, null, null, null, null, null, null, null, null, null, null, null,
        List.of(), List.of());
  }

  private static Version rich(final String version) {
    return new Version(
        version,
        PUBLISHED,
        "a demo",
        "https://example.test",
        "MIT",
        "git",
        "https://example.test/demo.git",
        "Ada",
        "ada@example.test",
        "https://ada.test",
        "https://example.test/issues",
        "bugs@example.test",
        null,
        List.of("alpha", "beta"),
        List.of(new Maintainer("Ada", "ada@example.test", null), new Maintainer(null, null, null)));
  }

  private static NpmPackageSnapshot snapshot(
      final @Nullable String scope, final Map<String, String> tags, final Version... versions) {
    return new NpmPackageSnapshot(scope, "demo", "2.0.0", CREATED, List.of(versions), tags);
  }

  private static Map<String, Object> build(
      final NpmPackageSnapshot snapshot,
      final Map<String, NpmTarballFacts> tarballs,
      final @Nullable String packageUrl) {
    return NpmPackumentBuilder.build(snapshot, tarballs, packageUrl, MODIFIED);
  }

  private static Map<String, Object> versionOf(
      final Map<String, Object> packument, final String version) {
    return (Map<String, Object>) ((Map<String, Object>) packument.get("versions")).get(version);
  }

  @Test
  @DisplayName("lists exactly the versions and tags of the rows, with the times")
  void versionsTagsAndTimes() {
    final var packument =
        build(
            snapshot(
                null,
                Map.of("latest", "2.0.0", "beta", "1.0.0"),
                version("1.0.0"),
                version("2.0.0")),
            Map.of(),
            null);

    assertThat(packument).containsEntry("name", "demo").containsEntry("_id", "demo");
    assertThat(((Map<String, Object>) packument.get("versions")).keySet())
        .containsExactly("1.0.0", "2.0.0");
    assertThat(packument.get("dist-tags")).isEqualTo(Map.of("latest", "2.0.0", "beta", "1.0.0"));
    assertThat(packument.get("time"))
        .isEqualTo(
            Map.of(
                "created", "2026-01-02T03:04:05.678Z",
                "modified", "2026-09-24T10:11:12.131Z",
                "1.0.0", "2026-02-03T04:05:06.789Z",
                "2.0.0", "2026-02-03T04:05:06.789Z"));
  }

  @Test
  @DisplayName("a scoped package is named with its scope, and the version id says so")
  void scopedName() {
    final var packument =
        build(snapshot("acme", Map.of("latest", "2.0.0"), version("2.0.0")), Map.of(), null);

    assertThat(packument).containsEntry("name", "@acme/demo").containsEntry("_id", "@acme/demo");
    assertThat(versionOf(packument, "2.0.0"))
        .containsEntry("name", "@acme/demo")
        .containsEntry("_id", "@acme/demo@2.0.0");
  }

  @Test
  @DisplayName("latest comes from the package row when the tags lack it")
  void latestFromThePackageRow() {
    final var packument = build(snapshot(null, Map.of(), version("2.0.0")), Map.of(), null);

    assertThat(packument.get("dist-tags")).isEqualTo(Map.of("latest", "2.0.0"));
  }

  @Test
  @DisplayName("the row's columns become the version's fields")
  void fieldsFromTheRow() {
    final var packument =
        build(snapshot(null, Map.of("latest", "2.0.0"), rich("2.0.0")), Map.of(), null);

    assertThat(versionOf(packument, "2.0.0"))
        .containsEntry("description", "a demo")
        .containsEntry("homepage", "https://example.test")
        .containsEntry("license", "MIT")
        .containsEntry("repository", Map.of("type", "git", "url", "https://example.test/demo.git"))
        .containsEntry(
            "author", Map.of("name", "Ada", "email", "ada@example.test", "url", "https://ada.test"))
        .containsEntry(
            "bugs", Map.of("url", "https://example.test/issues", "email", "bugs@example.test"))
        .containsEntry("keywords", List.of("alpha", "beta"))
        .containsEntry("maintainers", List.of(Map.of("name", "Ada", "email", "ada@example.test")));
  }

  @Test
  @DisplayName("what a row does not have is left out, not made up")
  void nothingIsInvented() {
    final var packument =
        build(snapshot(null, Map.of("latest", "2.0.0"), version("2.0.0")), Map.of(), null);

    assertThat(versionOf(packument, "2.0.0"))
        .containsOnlyKeys("name", "version", "_id")
        .doesNotContainKeys(
            "description",
            "license",
            "author",
            "repository",
            "bugs",
            "dependencies",
            "dist",
            "deprecated");
  }

  @Test
  @DisplayName("the top level takes the latest version's fields, like a publish does")
  void latestIsLiftedToTheTop() {
    final var packument =
        build(
            snapshot(null, Map.of("latest", "2.0.0"), version("1.0.0"), rich("2.0.0")),
            Map.of(),
            null);

    assertThat(packument)
        .containsEntry("description", "a demo")
        .containsEntry("license", "MIT")
        .containsEntry("keywords", List.of("alpha", "beta"));
  }

  @Test
  @DisplayName("a latest that no row backs is not lifted, and does not fail the rebuild")
  void danglingLatest() {
    final var packument =
        build(snapshot(null, Map.of("latest", "9.9.9"), version("1.0.0")), Map.of(), null);

    assertThat(packument.get("dist-tags")).isEqualTo(Map.of("latest", "9.9.9"));
    assertThat(packument).doesNotContainKey("description");
  }

  @Test
  @DisplayName("the tarball's manifest brings the dependencies, and the row fills what it lacks")
  void manifestFromTheTarball() {
    final var manifest = new LinkedHashMap<String, Object>();
    manifest.put("name", "demo");
    manifest.put("version", "2.0.0");
    manifest.put("dependencies", Map.of("a", "^1"));
    manifest.put("license", Map.of("type", "MIT", "url", "https://example.test/license"));
    manifest.put("main", "index.js");
    final var facts = new NpmTarballFacts(manifest, "sha", "sha512-x");

    final var packument =
        build(
            snapshot(null, Map.of("latest", "2.0.0"), rich("2.0.0")), Map.of("2.0.0", facts), null);

    assertThat(versionOf(packument, "2.0.0"))
        .containsEntry("dependencies", Map.of("a", "^1"))
        .containsEntry("main", "index.js")
        // The manifest is the source, the column only a fallback: the object form of license stays.
        .containsEntry("license", Map.of("type", "MIT", "url", "https://example.test/license"))
        .containsEntry("description", "a demo");
  }

  @Test
  @DisplayName("the row decides the name, the version and the deprecation")
  void rowWinsForIdentityAndDeprecation() {
    final var manifest =
        Map.<String, Object>of("name", "stale", "version", "0.0.1", "deprecated", "no");
    final var deprecated =
        new Version(
            "2.0.0", PUBLISHED, null, null, null, null, null, null, null, null, null, null,
            "use 3.0", List.of(), List.of());

    final var packument =
        build(
            snapshot(null, Map.of("latest", "2.0.0"), deprecated),
            Map.of("2.0.0", new NpmTarballFacts(manifest, "s", "i")),
            null);

    assertThat(versionOf(packument, "2.0.0"))
        .containsEntry("name", "demo")
        .containsEntry("version", "2.0.0")
        .containsEntry("deprecated", "use 3.0");
  }

  @Test
  @DisplayName("a version that is not deprecated does not take a deprecated from the manifest")
  void manifestCannotDeprecate() {
    final var manifest =
        Map.<String, Object>of("deprecated", "from the tarball", "dist", Map.of("tarball", "x"));
    final var packument =
        build(
            snapshot(null, Map.of("latest", "2.0.0"), version("2.0.0")),
            Map.of("2.0.0", new NpmTarballFacts(manifest, "s", "i")),
            null);

    assertThat(versionOf(packument, "2.0.0")).doesNotContainKey("deprecated");
    assertThat(versionOf(packument, "2.0.0").get("dist"))
        .as("the dist of the manifest is not the tarball's own")
        .isEqualTo(Map.of("shasum", "s", "integrity", "i"));
  }

  @Test
  @DisplayName("dist has the tarball URL from the registry address and the digests of the tarball")
  void dist() {
    final var packument =
        build(
            snapshot("acme", Map.of("latest", "2.0.0"), version("2.0.0")),
            Map.of("2.0.0", new NpmTarballFacts(Map.of(), "abc123", "sha512-zzz")),
            "http://localhost:9090/repo/@acme/demo");

    assertThat(versionOf(packument, "2.0.0").get("dist"))
        .isEqualTo(
            Map.of(
                "tarball", "http://localhost:9090/repo/@acme/demo/-/demo-2.0.0.tgz",
                "shasum", "abc123",
                "integrity", "sha512-zzz"));
  }

  @Test
  @DisplayName("without a registry address there is no tarball URL, without a tarball no digests")
  void distWithoutWhatItCannotKnow() {
    final var withDigestsOnly =
        build(
            snapshot(null, Map.of("latest", "2.0.0"), version("2.0.0")),
            Map.of("2.0.0", new NpmTarballFacts(Map.of(), "abc", "sha512-z")),
            " ");
    final var withUrlOnly =
        build(
            snapshot(null, Map.of("latest", "2.0.0"), version("2.0.0")),
            Map.of(),
            "http://h/repo/demo");

    assertThat(versionOf(withDigestsOnly, "2.0.0").get("dist"))
        .isEqualTo(Map.of("shasum", "abc", "integrity", "sha512-z"));
    assertThat(versionOf(withUrlOnly, "2.0.0").get("dist"))
        .isEqualTo(Map.of("tarball", "http://h/repo/demo/-/demo-2.0.0.tgz"));
  }
}
