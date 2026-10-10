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

import io.repsy.libs.storage.core.dtos.StoragePath;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("MavenGavUtils")
class MavenGavUtilsTest {

  @ParameterizedTest(name = "{0} is a {1} jar")
  @CsvSource({
    "com/acme/lib/1.0/lib-1.0-sources.jar, sources",
    "com/acme/lib/1.0/lib-1.0-javadoc.jar, javadoc",
    "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1-sources.jar, sources",
    "com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT-javadoc.jar, javadoc"
  })
  @DisplayName("tells the jar of a classifier by the classifier of its own GAV (RPS-1198)")
  void isClassifierJarTellsTheJarOfAClassifier(final String path, final String classifier) {
    assertThat(MavenGavUtils.isClassifierJar(path, classifier)).isTrue();
  }

  @ParameterizedTest(name = "{0} is not a {1} jar")
  @CsvSource({
    "com/acme/foo-sources/1.0/foo-sources-1.0.jar, sources",
    "com/acme/foo-sources/1.0/foo-sources-1.0.pom, sources",
    "com/acme/foo-sources/1.0/foo-sources-1.0-sources.pom, sources",
    "com/acme/lib-javadoc/1.0/lib-javadoc-1.0.jar, javadoc",
    "com/acme/lib/1.0/lib-1.0.jar, sources",
    "com/acme/lib/1.0/lib-1.0-sources.jar, javadoc",
    "com/acme/lib/1.0/lib-1.0-Sources.jar, sources",
    "com/acme/lib/1.0/lib-1.0-my-sources.jar, sources",
    "com/acme/lib/1.0/lib-1.0-sources.jar.sha1, sources",
    "com/acme/lib/1.0/lib-1.0-sources.jar.md5, sources",
    "com/acme/lib/1.0/lib-1.0-sources.jar.asc, sources",
    "com/acme/lib/1.0/lib-1.0-sources.zip, sources",
    "com/acme/lib/1.0/stray.txt, sources"
  })
  @DisplayName(
      "a substring, a checksum, a signature or another extension is no such jar (RPS-1198)")
  void isClassifierJarRefusesEverythingElse(final String path, final String classifier) {
    assertThat(MavenGavUtils.isClassifierJar(path, classifier)).isFalse();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "com/acme/lib/1.0/lib-1.0.jar",
        "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1.jar",
        "com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar"
      })
  @DisplayName("the jar of a version itself is its main jar (RPS-1589)")
  void isMainJarTellsTheJarOfTheVersion(final String path) {
    assertThat(MavenGavUtils.isMainJar(StoragePath.of(UUID.randomUUID(), path))).isTrue();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "com/acme/lib/1.0/lib-1.0-sources.jar",
        "com/acme/lib/1.0/lib-1.0.jar.sha1",
        "com/acme/lib/1.0/lib-1.0.jar.asc",
        "com/acme/lib/1.0/lib-1.0.pom",
        "com/acme/lib/1.0/lib-1.0.war",
        "com/acme/lib/1.0/stray.jar",
        "com/acme/lib/maven-metadata.xml"
      })
  @DisplayName("a classifier jar, a checksum, a signature or another file is not a main jar")
  void isMainJarRefusesEverythingElse(final String path) {
    assertThat(MavenGavUtils.isMainJar(StoragePath.of(UUID.randomUUID(), path))).isFalse();
  }

  @ParameterizedTest(name = "{0} is {1}:{2}:{3} (classifier {4}, extension {5})")
  @CsvSource(
      nullValues = "NULL",
      value = {
        "com/acme/lib/1.0/lib-1.0.jar, com.acme, lib, 1.0, NULL, jar",
        "com/acme/lib/1.0/lib-1.0-sources.jar, com.acme, lib, 1.0, sources, jar",
        "com/acme/lib/1.0/lib-1.0.tar.gz, com.acme, lib, 1.0, NULL, tar.gz",
        "com/acme/lib/1.0/lib-1.0.module, com.acme, lib, 1.0, NULL, module",
        "com/acme/lib/1.0/lib-1.0-kotlin-tooling-metadata.json, com.acme, lib, 1.0,"
            + " kotlin-tooling-metadata, json",
        "com/acme/lib/1.0/lib-1.0.klib, com.acme, lib, 1.0, NULL, klib",
        "com/acme/lib/1.0/lib-1.0.jar.asc, com.acme, lib, 1.0, NULL, jar",
        "com/acme/lib/1.0/lib-1.0.jar.asc.sha1, com.acme, lib, 1.0, NULL, jar",
        "com/acme/lib/1.0/lib-1.0.jar.md5, com.acme, lib, 1.0, NULL, jar",
        "com/acme/lib/1.0/lib-1.0.module.sha512, com.acme, lib, 1.0, NULL, module",
        "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1.jar.sha1, com.acme, lib,"
            + " 1.0-20260921.101010-1, NULL, jar",
        "com/acme/lib_2.13/1.0/lib_2.13-1.0.jar, com.acme, lib_2.13, 1.0, NULL, jar",
        "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1-sources.jar, com.acme, lib,"
            + " 1.0-20260921.101010-1, sources, jar",
        "com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar, com.acme, lib, 1.0-SNAPSHOT, NULL, jar",
        "com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT-sources.jar, com.acme, lib, 1.0-SNAPSHOT,"
            + " sources, jar",
        "com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.module, com.acme, lib, 1.0-SNAPSHOT, NULL,"
            + " module",
        "com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar.sha1, com.acme, lib, 1.0-SNAPSHOT, NULL,"
            + " jar",
        "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1.pom.asc, com.acme, lib,"
            + " 1.0-20260921.101010-1, NULL, pom",
        "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-12-kotlin-tooling-metadata.json,"
            + " com.acme, lib, 1.0-20260921.101010-12, kotlin-tooling-metadata, json",
        "com/acme/lib/1.0-beta-SNAPSHOT/lib-1.0-beta-20260921.101010-1.jar, com.acme, lib,"
            + " 1.0-beta-20260921.101010-1, NULL, jar",
        "com/acme/lib/2.0.0-rc.1-SNAPSHOT/lib-2.0.0-rc.1-SNAPSHOT.jar, com.acme, lib,"
            + " 2.0.0-rc.1-SNAPSHOT, NULL, jar",
        "com/acme/lib/SNAPSHOT/lib-20260921.101010-1.jar, com.acme, lib, 20260921.101010-1,"
            + " NULL, jar",
        "com/acme/lib_2.13/1.0-SNAPSHOT/lib_2.13-1.0-20260921.101010-1.jar, com.acme, lib_2.13,"
            + " 1.0-20260921.101010-1, NULL, jar",
        "com/acme/lib-core/1.0-SNAPSHOT/lib-core-1.0-SNAPSHOT.jar, com.acme, lib-core,"
            + " 1.0-SNAPSHOT, NULL, jar",
        "com/acme/bar.pom.utils/1.0/bar.pom.utils-1.0.jar, com.acme, bar.pom.utils, 1.0, NULL, jar",
        "com/acme/bar.pom.utils/1.0/bar.pom.utils-1.0.pom, com.acme, bar.pom.utils, 1.0, NULL, pom",
        "com/acme/bar.pom.utils/1.0-SNAPSHOT/bar.pom.utils-1.0-20260921.101010-1.jar, com.acme,"
            + " bar.pom.utils, 1.0-20260921.101010-1, NULL, jar"
      })
  @DisplayName(
      "calculates the GAV of the files real Maven, Gradle and sbt clients send, with the signature"
          + " and checksum suffixes stripped")
  void calculatesTheGavOfTheFilesRealClientsSend(
      final String path,
      final String groupId,
      final String artifactId,
      final String version,
      final String classifier,
      final String extension) {
    final var gav = MavenGavUtils.getGavByFile(StoragePath.of(UUID.randomUUID(), path));

    assertThat(gav).isNotNull();
    assertThat(gav.getGroupId()).isEqualTo(groupId);
    assertThat(gav.getArtifactId()).isEqualTo(artifactId);
    assertThat(gav.getVersion()).isEqualTo(version);
    assertThat(gav.getClassifier()).isEqualTo(classifier);
    assertThat(gav.getExtension()).isEqualTo(extension);
  }

  @ParameterizedTest(name = "{0} has no GAV")
  @ValueSource(
      strings = {
        "com/acme/lib/1.0-SNAPSHOT/lib-2.0-SNAPSHOT.jar",
        "com/acme/lib/1.0-SNAPSHOT/lib-1.1-SNAPSHOT.jar",
        "com/acme/lib/1.0-SNAPSHOT/lib-2.0-20260921.101010-1.jar",
        "com/acme/lib/1.0-SNAPSHOT/lob-1.0-SNAPSHOT.jar",
        "com/acme/lib/1.0-SNAPSHOT/Lib-1.0-SNAPSHOT.jar",
        "com/acme/lib/1.0-SNAPSHOT/lib-core-1.0-SNAPSHOT.jar",
        "com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOTX.jar",
        "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921-101010-1.jar",
        "com/acme/lib/1.0-SNAPSHOT/lib-1.0-2026092.1010101-1.jar",
        "com/acme/lib/1.0-SNAPSHOT/lib-2.0-SNAPSHOT.jar.sha1",
        "com/acme/lib/1.0-SNAPSHOT/lib-2.0-SNAPSHOT.pom.asc",
        "com/acme/lib/1.0-beta-SNAPSHOT/lib-1.0-20260921.101010-1.jar"
      })
  @DisplayName(
      "a file in a SNAPSHOT directory must carry that directory's artifactId and base version"
          + " (RPS-1184)")
  void noGavForASnapshotFileOfAnotherArtifactOrVersion(final String path) {
    assertThat(MavenGavUtils.getGavByFile(StoragePath.of(UUID.randomUUID(), path))).isNull();
  }

  @ParameterizedTest(name = "{0} has no GAV")
  @ValueSource(
      strings = {
        "io/stray.txt",
        "stray.txt",
        "com/acme/lib/1.0/other-1.0.jar",
        "com/acme/lib/1.0/lib-2.0.jar",
        "com/acme/lib/1.0/Lib-1.0.jar",
        "com/acme/lib/1.0/lib-1.0",
        "com/acme/lib/1.0/jars/lib.jar",
        "com/acme/lib/1.0-SNAPSHOT/stray.txt",
        "com/acme/lib/1.0-SNAPSHOT/b-1.0-SNAPSHOT.jar",
        "archetype-catalog.xml",
        "io/stray.txt.sha1",
        "com/acme/lib/1.0/other-1.0.jar.sha1"
      })
  @DisplayName("finds no GAV for a path outside the layout, nor for a checksum of it")
  void noGavOutsideTheLayout(final String path) {
    assertThat(MavenGavUtils.getGavByFile(StoragePath.of(UUID.randomUUID(), path))).isNull();
  }

  @ParameterizedTest(name = "{0} is in a snapshot version directory: {1}")
  @CsvSource({
    "com/acme/lib/1.0-SNAPSHOT/maven-metadata.xml.sha1, true",
    "com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar, true",
    "com/acme/lib/1.0-SNAPSHOT/maven-metadata.xml.asc, true",
    "com/acme/lib/maven-metadata.xml.sha1, false",
    "com/acme/lib/maven-metadata.xml.asc, false",
    "com/acme/lib/1.0/maven-metadata.xml.md5, false",
    "maven-metadata.xml, false"
  })
  @DisplayName("tells a file of a SNAPSHOT version directory by its directory (RPS-1183, RPS-1185)")
  void recognisesAFileOfASnapshotVersionDirectory(final String path, final boolean expected) {
    assertThat(MavenGavUtils.isSnapshotVersionDirectoryFile(path)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0} has GAV {1}:{2}:{3}")
  @CsvSource(
      nullValues = "NULL",
      value = {
        // Version-level metadata: only a SNAPSHOT directory is unambiguous (RPS-1195).
        "com/acme/lib/1.0-SNAPSHOT/maven-metadata.xml, com.acme, lib, 1.0-SNAPSHOT",
        "com/acme/lib/1.0-SNAPSHOT/maven-metadata.xml.sha1, com.acme, lib, 1.0-SNAPSHOT",
        "com/acme/lib/1.0-SNAPSHOT/maven-metadata.xml.md5, com.acme, lib, 1.0-SNAPSHOT",
        "com/acme/lib/1.0-SNAPSHOT/maven-metadata.xml.asc, com.acme, lib, 1.0-SNAPSHOT",
        "com/acme/sub/lib/1.0-SNAPSHOT/maven-metadata.xml, com.acme.sub, lib, 1.0-SNAPSHOT",
        // Artifact-level metadata has no version segment: used to be misparsed as group "com",
        // artifact "acme", version "lib" (RPS-1177), now answers no GAV instead.
        "com/acme/lib/maven-metadata.xml, NULL, NULL, NULL",
        "com/acme/lib/maven-metadata.xml.sha1, NULL, NULL, NULL",
        "com/acme/lib/maven-metadata.xml.asc, NULL, NULL, NULL",
        // Group-level metadata (plugins).
        "com/acme/maven-metadata.xml, NULL, NULL, NULL",
        "com/acme/maven-metadata.xml.sha1, NULL, NULL, NULL",
        // A hand-crafted release version-level path is indistinguishable from an artifact-level
        // one, the documented, accepted gap (RPS-1195).
        "com/acme/lib/1.0/maven-metadata.xml, NULL, NULL, NULL",
        "com/acme/lib/1.0/maven-metadata.xml.sha1, NULL, NULL, NULL",
      })
  @DisplayName(
      "calculates the basic GAV of a metadata-family file, without misparsing an artifact-level"
          + " path as if it had a version (RPS-1177, RPS-1195)")
  void calculatesTheBasicGavOfAMetadataFamilyFile(
      final String path, final String groupId, final String artifactId, final String version) {
    final var gav = MavenGavUtils.getGavByFile(StoragePath.of(UUID.randomUUID(), path));

    if (groupId == null) {
      assertThat(gav).isNull();
    } else {
      assertThat(gav).isNotNull();
      assertThat(gav.getGroupId()).isEqualTo(groupId);
      assertThat(gav.getArtifactId()).isEqualTo(artifactId);
      assertThat(gav.getVersion()).isEqualTo(version);
    }
  }

  @Test
  @DisplayName(
      "an artifactId that literally contains \"maven-metadata.xml\" is parsed as a real artifact,"
          + " not routed to the metadata branch by a substring match over the whole path"
          + " (RPS-1177)")
  void artifactIdContainingTheMetadataFilenameSubstringIsNotMisrouted() {
    final var path = "com/acme/maven-metadata.xml-plugin/1.0/maven-metadata.xml-plugin-1.0.jar";

    final var gav = MavenGavUtils.getGavByFile(StoragePath.of(UUID.randomUUID(), path));

    assertThat(gav).isNotNull();
    assertThat(gav.getGroupId()).isEqualTo("com.acme");
    assertThat(gav.getArtifactId()).isEqualTo("maven-metadata.xml-plugin");
    assertThat(gav.getVersion()).isEqualTo("1.0");
    assertThat(gav.getExtension()).isEqualTo("jar");
  }

  @ParameterizedTest(name = "{0} is {1}:{2}:{3} (classifier {4}, extension {5})")
  @CsvSource(
      nullValues = "NULL",
      value = {
        // M2GavCalculator splits the tail after "<artifactId>-<version>" at its FIRST dot, so a
        // dotted classifier is mis-split: the real classifier "2.0" becomes "2", and the real
        // extension "jar" becomes "0.jar" (RPS-1187, won't-fix: see AbstractMavenProtocolFacade,
        // isScannableArtifact requires a null classifier, so the wrong-but-non-null classifier
        // still yields the correct scanner decision by accident).
        "com/acme/lib/1.0/lib-1.0-2.0.jar, com.acme, lib, 1.0, 2, 0.jar",
        "com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT-20260921.101010-1.jar, com.acme, lib,"
            + " 1.0-SNAPSHOT, 20260921, 101010-1.jar",
      })
  @DisplayName("pins the wrong-but-harmless split of a dotted classifier (RPS-1187, won't-fix)")
  void pinsTheDottedClassifierMisparse(
      final String path,
      final String groupId,
      final String artifactId,
      final String version,
      final String classifier,
      final String extension) {
    final var gav = MavenGavUtils.getGavByFile(StoragePath.of(UUID.randomUUID(), path));

    assertThat(gav).isNotNull();
    assertThat(gav.getGroupId()).isEqualTo(groupId);
    assertThat(gav.getArtifactId()).isEqualTo(artifactId);
    assertThat(gav.getVersion()).isEqualTo(version);
    assertThat(gav.getClassifier()).isEqualTo(classifier);
    assertThat(gav.getExtension()).isEqualTo(extension);
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.pom, true",
    "com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar, true",
    "com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT-sources.jar, true",
    "com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.pom.sha1, true",
    "com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar.asc, true",
    "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1.pom, false",
    "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1.jar.sha1, false",
    "com/acme/lib/1.0/lib-1.0.pom, false",
    "com/acme/lib/1.0/lib-1.0.jar, false"
  })
  @DisplayName("only the literal files of a snapshot are non-unique snapshot files (RPS-1328)")
  void tellsANonUniqueSnapshotFile(final String path, final boolean nonUnique) {
    final var gav = MavenGavUtils.convertPathToGav(path);

    assertThat(gav).isNotNull();
    assertThat(MavenGavUtils.isNonUniqueSnapshotFile(gav)).isEqualTo(nonUnique);
  }

  @ParameterizedTest(name = "{0} is suitable for GAV extraction: {1}")
  @CsvSource({
    "lib-1.0.jar, true",
    "lib-1.0.pom, true",
    "maven-metadata.xml, false",
    "maven-metadata.xml.sha1, false",
    "MAVEN-METADATA.XML.asc, false"
  })
  @DisplayName("the metadata family is classified by content, any other file by its path")
  void tellsFilesWhoseGavComesFromTheirPath(final String fileName, final boolean expected) {
    assertThat(MavenGavUtils.isFileSuitableForGavExtraction(fileName)).isEqualTo(expected);
  }
}
