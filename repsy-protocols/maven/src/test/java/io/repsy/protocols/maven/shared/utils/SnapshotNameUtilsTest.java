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
package io.repsy.protocols.maven.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("SnapshotNameUtils")
class SnapshotNameUtilsTest {

  private static StorageItemInfo item(
      final String name, final String path, final boolean directory) {
    return StorageItemInfo.builder().name(name).path(path).directory(directory).build();
  }

  @Test
  @DisplayName("a release version has to sign every signable file of its directory")
  void aReleaseVersionSignsEverySignableFile() {
    final var files =
        List.of(
            "lib-1.0.pom",
            "lib-1.0.pom.asc",
            "lib-1.0.pom.sha1",
            "lib-1.0.jar",
            "lib-1.0-sources.jar",
            "lib-1.0-sources.jar.asc",
            "lib-1.0.module",
            "maven-metadata.xml");

    assertThat(SnapshotNameUtils.filesToSign("com/acme/lib/1.0", files))
        .containsExactlyInAnyOrder(
            "lib-1.0.pom", "lib-1.0.jar", "lib-1.0-sources.jar", "lib-1.0.module");
  }

  @Test
  @DisplayName("a snapshot version only has to sign the files of its newest build")
  void aSnapshotVersionSignsItsNewestBuildOnly() {
    final var files =
        List.of(
            "lib-1.0-20260921.101010-1.pom",
            "lib-1.0-20260921.101010-1.jar",
            "lib-1.0-20260921.101010-2.pom",
            "lib-1.0-20260921.101010-2.jar",
            "lib-1.0-20260921.101010-2-sources.jar",
            "lib-1.0-20260921.101010-10.pom",
            "lib-1.0-20260921.101010-10.jar",
            "lib-1.0-20260921.101010-10.jar.asc",
            "lib-1.0-20260920.235959-99.jar",
            "lib-1.0-20260921.101010-10.jar.sha1",
            "maven-metadata.xml");

    assertThat(SnapshotNameUtils.filesToSign("com/acme/lib/1.0-SNAPSHOT", files))
        .containsExactlyInAnyOrder(
            "lib-1.0-20260921.101010-10.pom", "lib-1.0-20260921.101010-10.jar");
  }

  @Test
  @DisplayName("a timestamped snapshot build is newer than a literal SNAPSHOT file")
  void aTimestampedBuildBeatsALiteralSnapshotFile() {
    assertThat(
            SnapshotNameUtils.filesToSign(
                "com/acme/lib/1.0-SNAPSHOT",
                List.of("lib-1.0-SNAPSHOT.jar", "lib-1.0-20260921.101010-1.jar")))
        .containsExactly("lib-1.0-20260921.101010-1.jar");
    assertThat(
            SnapshotNameUtils.filesToSign(
                "com/acme/lib/1.0-SNAPSHOT",
                List.of("lib-1.0-SNAPSHOT.jar", "lib-1.0-SNAPSHOT.pom")))
        .containsExactlyInAnyOrder("lib-1.0-SNAPSHOT.jar", "lib-1.0-SNAPSHOT.pom");
  }

  @Test
  @DisplayName("the newest POM of a snapshot directory is the highest build, a literal the oldest")
  void picksTheNewestSnapshotPom() {
    final var files =
        List.of(
            "lib-1.0-SNAPSHOT.pom",
            "lib-1.0-20260921.101010-2.pom",
            "lib-1.0-20260921.101010-10.pom",
            "lib-1.0-20260920.235959-99.pom",
            "lib-1.0-20260921.101010-10.pom.sha1",
            "lib-1.0-20260921.101010-11-sources.pom",
            "other-1.0-20260922.101010-1.pom",
            "lib-1.0-20260921.101010-11.jar",
            "maven-metadata.xml");

    assertThat(SnapshotNameUtils.newestSnapshotPomName("lib", "1.0-SNAPSHOT", files))
        .isEqualTo("lib-1.0-20260921.101010-10.pom");
  }

  @Test
  @DisplayName("a literal POM is the answer when no timestamped POM is stored")
  void picksTheLiteralSnapshotPom() {
    assertThat(
            SnapshotNameUtils.newestSnapshotPomName(
                "lib", "1.0-SNAPSHOT", List.of("lib-1.0-SNAPSHOT.jar", "lib-1.0-SNAPSHOT.pom")))
        .isEqualTo("lib-1.0-SNAPSHOT.pom");
    assertThat(
            SnapshotNameUtils.newestSnapshotPomName("lib", "SNAPSHOT", List.of("lib-SNAPSHOT.pom")))
        .isEqualTo("lib-SNAPSHOT.pom");
  }

  @Test
  @DisplayName("no snapshot POM is answered for a release, a missing POM or another artifact")
  void picksNoSnapshotPom() {
    assertThat(SnapshotNameUtils.newestSnapshotPomName("lib", "1.0", List.of("lib-1.0.pom")))
        .isNull();
    assertThat(SnapshotNameUtils.newestSnapshotPomName("lib", "1.0-SNAPSHOT", List.of())).isNull();
    assertThat(
            SnapshotNameUtils.newestSnapshotPomName(
                "lib", "1.0-SNAPSHOT", List.of("lib-1.0-SNAPSHOT.jar", "other-1.0-SNAPSHOT.pom")))
        .isNull();
    assertThat(
            SnapshotNameUtils.newestSnapshotPomName(
                "l.b", "1.0-SNAPSHOT", List.of("lib-1.0-SNAPSHOT.pom")))
        .isNull();
  }

  @Test
  @DisplayName("the newest main jar of a snapshot is the highest build, a number compared as such")
  void picksTheNewestSnapshotJar() {
    final var files =
        List.of(
            "lib-1.0-SNAPSHOT.jar",
            "lib-1.0-20260921.101010-9.jar",
            "lib-1.0-20260921.101010-10.jar",
            "lib-1.0-20260920.235959-99.jar",
            "lib-1.0-20260921.101010-11.pom");

    assertThat(SnapshotNameUtils.newestSnapshotMainFileName("lib", "1.0-SNAPSHOT", "jar", files))
        .isEqualTo("lib-1.0-20260921.101010-10.jar");
    assertThat(SnapshotNameUtils.newestSnapshotMainFileName("lib", "1.0-SNAPSHOT", "pom", files))
        .isEqualTo("lib-1.0-20260921.101010-11.pom");
  }

  @Test
  @DisplayName("a literal snapshot jar is the answer when no timestamped jar is stored (RPS-1420)")
  void picksTheLiteralSnapshotJar() {
    assertThat(
            SnapshotNameUtils.newestSnapshotMainFileName(
                "lib",
                "1.0-SNAPSHOT",
                "jar",
                List.of("lib-1.0-SNAPSHOT.jar", "lib-1.0-SNAPSHOT.pom")))
        .isEqualTo("lib-1.0-SNAPSHOT.jar");
    assertThat(
            SnapshotNameUtils.newestSnapshotMainFileName(
                "lib", "1.0-SNAPSHOT", "war", List.of("lib-1.0-SNAPSHOT.war")))
        .isEqualTo("lib-1.0-SNAPSHOT.war");
  }

  @Test
  @DisplayName("classifier jars, checksums, signatures and other artifacts are no main jar")
  void picksNoSnapshotJar() {
    assertThat(
            SnapshotNameUtils.newestSnapshotMainFileName(
                "lib",
                "1.0-SNAPSHOT",
                "jar",
                List.of(
                    "lib-1.0-SNAPSHOT-sources.jar",
                    "lib-1.0-SNAPSHOT-javadoc.jar",
                    "lib-1.0-20260921.101010-1-sources.jar",
                    "lib-1.0-SNAPSHOT.jar.sha1",
                    "lib-1.0-SNAPSHOT.jar.asc",
                    "other-1.0-SNAPSHOT.jar",
                    "lib-1.0-SNAPSHOT.pom",
                    "maven-metadata.xml")))
        .isNull();
    assertThat(
            SnapshotNameUtils.newestSnapshotMainFileName(
                "lib", "1.0", "jar", List.of("lib-1.0.jar")))
        .isNull();
    assertThat(
            SnapshotNameUtils.newestSnapshotMainFileName("lib", "1.0-SNAPSHOT", "jar", List.of()))
        .isNull();
  }

  @Test
  @DisplayName("lists the files that sit directly in the version directory, not nested ones")
  void listsTheFilesOfTheVersionDirectoryOnly() {
    final var items =
        List.of(
            item("lib-1.0.jar", "/data/key/com/acme/lib/1.0/lib-1.0.jar", false),
            item("lib-1.0.jar", "\\data\\key\\com\\acme\\lib\\1.0\\lib-1.0.jar", false),
            item("1.0", "/data/key/com/acme/lib/1.0", true),
            item("x.jar", "/data/key/com/acme/lib/1.0/nested/x.jar", false),
            item("y.jar", "/data/key/com/acme/lib/1.0.1/y.jar", false));

    assertThat(SnapshotNameUtils.versionDirFileNames("com/acme/lib/1.0", items))
        .containsExactly("lib-1.0.jar", "lib-1.0.jar");
  }

  @ParameterizedTest(name = "{0} is a snapshot version: {1}")
  @CsvSource({
    "1.0-SNAPSHOT, true",
    "SNAPSHOT, true",
    "1.0-20260921.101010-1, true",
    "1.0, false",
    "1.0-snapshot, true"
  })
  @DisplayName(
      "tells a snapshot version like Maven does: the suffix, any case, or a timestamped build")
  void recognisesASnapshotVersion(final String version, final boolean expected) {
    assertThat(SnapshotNameUtils.isSnapshot(version)).isEqualTo(expected);
  }
}
