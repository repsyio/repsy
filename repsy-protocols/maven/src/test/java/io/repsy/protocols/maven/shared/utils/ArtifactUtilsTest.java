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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.model.Model;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamResource;

@DisplayName("ArtifactUtils")
class ArtifactUtilsTest {

  private static final String VALID_POM =
      """
      <project>
        <modelVersion>4.0.0</modelVersion>
        <groupId>com.example</groupId>
        <artifactId>lib</artifactId>
        <version>1.0</version>
      </project>
      """;

  private static final String GROUP_METADATA =
      """
      <metadata><plugins><plugin><name>Acme Maven Plugin</name><prefix>acme</prefix>\
      <artifactId>acme-maven-plugin</artifactId></plugin></plugins></metadata>""";

  private static final String ARTIFACT_METADATA =
      """
      <metadata><groupId>com.acme</groupId><artifactId>lib</artifactId><versioning>\
      <release>1.0</release><versions><version>1.0</version></versions>\
      <lastUpdated>20260921101010</lastUpdated></versioning></metadata>""";

  private static final String VERSION_METADATA =
      """
      <metadata modelVersion="1.1.0"><groupId>com.acme</groupId><artifactId>lib</artifactId>\
      <version>1.0-SNAPSHOT</version><versioning><snapshot><timestamp>20260921.101010</timestamp>\
      <buildNumber>1</buildNumber></snapshot><lastUpdated>20260921101010</lastUpdated>\
      <snapshotVersions><snapshotVersion><extension>jar</extension>\
      <value>1.0-20260921.101010-1</value><updated>20260921101010</updated></snapshotVersion>\
      <snapshotVersion><extension>pom</extension><value>1.0-20260921.101010-1</value>\
      <updated>20260921101010</updated></snapshotVersion></snapshotVersions></versioning>\
      </metadata>""";

  private static Metadata metadata(final String xml) throws Exception {
    return ArtifactUtils.readMetadata(xml.getBytes(UTF_8));
  }

  @Test
  @DisplayName(
      "plugin metadata needs at least one plugin, an empty plugin list is not plugin metadata")
  void pluginMetadataNeedsAtLeastOnePlugin() throws Exception {
    assertThat(ArtifactUtils.isPluginMetadata(metadata("<metadata/>"))).isFalse();
    assertThat(ArtifactUtils.isPluginMetadata(metadata(ARTIFACT_METADATA))).isFalse();
    assertThat(ArtifactUtils.isPluginMetadata(metadata(VERSION_METADATA))).isFalse();
    assertThat(ArtifactUtils.isPluginMetadata(metadata(GROUP_METADATA))).isTrue();
  }

  @Test
  @DisplayName("version-level metadata is recognised by the version it names")
  void versionLevelMetadataIsRecognisedByItsVersion() throws Exception {
    assertThat(ArtifactUtils.isVersionLevelMetadata(metadata(VERSION_METADATA))).isTrue();
    assertThat(ArtifactUtils.isVersionLevelMetadata(metadata(ARTIFACT_METADATA))).isFalse();
    assertThat(ArtifactUtils.isVersionLevelMetadata(metadata(GROUP_METADATA))).isFalse();
    assertThat(ArtifactUtils.isVersionLevelMetadata(metadata("<metadata/>"))).isFalse();
    assertThat(
            ArtifactUtils.isVersionLevelMetadata(
                metadata("<metadata><version> </version></metadata>")))
        .isFalse();
  }

  @Test
  @DisplayName("answers malformed metadata with a fixed msgId that does not echo the file")
  void malformedMetadataYieldsFixedMessageId() {
    final var secret = "do-not-reflect-this-marker";
    final var malformed = "<metadata><versioning><!-- " + secret + " -->";

    assertThatThrownBy(() -> ArtifactUtils.readMetadata(malformed.getBytes(UTF_8)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("malformedMetadataFile")
        .satisfies(e -> assertThat(e.toString()).doesNotContain(secret));
  }

  @ParameterizedTest(name = "{0} is a {1} jar")
  @CsvSource({
    "com/acme/lib/1.0/lib-1.0-sources.jar, sources",
    "com/acme/lib/1.0/lib-1.0-javadoc.jar, javadoc",
    "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1-sources.jar, sources",
    "com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT-javadoc.jar, javadoc"
  })
  @DisplayName("tells the jar of a classifier by the classifier of its own GAV (RPS-1198)")
  void isClassifierJarTellsTheJarOfAClassifier(final String path, final String classifier) {
    assertThat(ArtifactUtils.isClassifierJar(path, classifier)).isTrue();
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
    assertThat(ArtifactUtils.isClassifierJar(path, classifier)).isFalse();
  }

  @Test
  @DisplayName("reads a well-formed POM")
  void readsAWellFormedPom() {
    final var model = ArtifactUtils.readModel(new ByteArrayResource(VALID_POM.getBytes(UTF_8)));

    assertThat(model).isNotNull();
    assertThat(model.getGroupId()).isEqualTo("com.example");
    assertThat(model.getArtifactId()).isEqualTo("lib");
    assertThat(model.getVersion()).isEqualTo("1.0");
  }

  @Test
  @DisplayName("answers a malformed POM with a fixed msgId that does not echo the file")
  void malformedPomYieldsFixedMessageId() {
    final var secret = "do-not-reflect-this-marker";
    final var malformed = "<project><modelVersion>4.0.0</modelVersion><!-- " + secret + " -->";

    assertThatThrownBy(
            () -> ArtifactUtils.readModel(new ByteArrayResource(malformed.getBytes(UTF_8))))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("malformedPomFile")
        .satisfies(e -> assertThat(e.toString()).doesNotContain(secret));
  }

  @Test
  @DisplayName("answers an unreadable POM with the same fixed msgId")
  void unreadablePomYieldsFixedMessageId() {
    final var unreadable =
        new InputStreamResource(
            new InputStream() {
              @Override
              public int read() throws IOException {
                throw new IOException("disk failure with /secret/path");
              }
            });

    assertThatThrownBy(() -> ArtifactUtils.readModel(unreadable))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("malformedPomFile");
  }

  @Test
  @DisplayName("reads a well-formed POM from a stream and leaves the stream open")
  void readsAWellFormedPomFromAStream() {
    final var closed = new boolean[1];
    final var stream =
        new ByteArrayInputStream(VALID_POM.getBytes(UTF_8)) {
          @Override
          public void close() throws IOException {
            closed[0] = true;
            super.close();
          }
        };

    final var model = ArtifactUtils.readModel(stream);

    assertThat(model).isNotNull();
    assertThat(model.getArtifactId()).isEqualTo("lib");
    assertThat(closed[0]).isFalse();
  }

  @Test
  @DisplayName("answers a malformed POM read from a stream with the fixed msgId")
  void malformedPomStreamYieldsFixedMessageId() {
    final var malformed = new ByteArrayInputStream("<project><groupId>".getBytes(UTF_8));

    assertThatThrownBy(() -> ArtifactUtils.readModel(malformed))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("malformedPomFile");
  }

  @ParameterizedTest(name = "{0} is parsed as a POM: {1}")
  @CsvSource({
    "com/example/lib/1.0/lib-1.0.pom, true",
    "com/example/lib/1.0/LIB-1.0.POM, true",
    "com/example/lib/1.0/lib-1.0.pom.asc, false",
    "com/example/lib/1.0/lib-1.0.pom.sha1, false",
    "com/example/lib/1.0/lib-1.0.pom.md5, false",
    "com/example/lib/1.0/lib-1.0.jar, false",
    "com/example/lib/maven-metadata.xml, false",
    "com/acme/bar.pom.utils/1.0/bar.pom.utils-1.0.jar, false",
    "com/acme/bar.pom.utils/1.0/bar.pom.utils-1.0.jar.asc, false",
    "com/acme/bar.pom.utils/1.0/bar.pom.utils-1.0.jar.sha1, false",
    "com/acme/bar.pom.utils/1.0/bar.pom.utils-1.0.pom, true",
    "com/acme/bar.pom.utils/1.0/bar.pom.utils-1.0.pom.asc, false",
    "com/acme/bar.pom.utils/1.0/bar.pom.utils-1.0.pom.sha1, false",
    "com/acme/bar.pom.utils/maven-metadata.xml, false",
    "com/acme/bar.pom.utils/maven-metadata.xml.asc, false",
    "com/acme/x.pom/1.0/x.pom-1.0.jar, false",
    "com/acme/x.pom/1.0/x.pom-1.0.pom, true",
    "com/acme/lib/1.0.pom/lib-1.0.pom.jar, false",
    "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1.pom, true",
    "com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.pom, true",
    "x, false"
  })
  @DisplayName(
      "tells the POMs the artifact service parses from the files stored beside them, by the file"
          + " name alone (RPS-1196)")
  void recognisesThePomsToParse(final String path, final boolean expected) {
    final var storagePath = StoragePath.of(UUID.randomUUID(), path);

    assertThat(ArtifactUtils.isPomToParse(storagePath)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0} is a POM signature: {1}")
  @CsvSource({
    "com/acme/lib/1.0/lib-1.0.pom.asc, true",
    "LIB-1.0.POM.asc, true",
    "lib-1.0.pom.ASC, false",
    "lib-1.0.jar.asc, false",
    "lib-1.0.pom.asc.sha1, false",
    "com/acme/bar.pom.utils/1.0/bar.pom.utils-1.0.jar.asc, false",
    "com/acme/bar.pom.utils/1.0/bar.pom.utils-1.0.pom.asc, true",
    "com/acme/bar.pom.utils/maven-metadata.xml.asc, false",
    "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1.pom.asc, true"
  })
  @DisplayName("tells the .asc signature of a POM by its file name alone (RPS-1196)")
  void recognisesAPomSignature(final String path, final boolean expected) {
    final var storagePath = StoragePath.of(UUID.randomUUID(), path);

    assertThat(ArtifactUtils.isPomSignature(storagePath)).isEqualTo(expected);
  }

  @Test
  @DisplayName("answers a binary POM body with the same fixed msgId (RPS-1196)")
  void binaryBodyYieldsFixedMessageId() {
    final var binary = new byte[] {'P', 'K', 3, 4, 0, 0};

    assertThatThrownBy(() -> ArtifactUtils.readModel(new ByteArrayInputStream(binary)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("malformedPomFile");
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
    final var gav = ArtifactUtils.getGavByFile(StoragePath.of(UUID.randomUUID(), path));

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
    assertThat(ArtifactUtils.getGavByFile(StoragePath.of(UUID.randomUUID(), path))).isNull();
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
    assertThat(ArtifactUtils.getGavByFile(StoragePath.of(UUID.randomUUID(), path))).isNull();
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
    assertThat(ArtifactUtils.isSnapshotVersionDirectoryFile(path)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0} is a metadata signature: {1}")
  @CsvSource({
    "maven-metadata.xml.asc, true",
    "MAVEN-METADATA.XML.asc, true",
    "maven-metadata.xml.asc.sha1, false",
    "maven-metadata.xml, false",
    "lib-1.0.pom.asc, false",
    "lib-1.0.jar.asc, false",
    "maven-metadata.xml.ASC, false",
    "maven-metadata.xml-plugin-1.0.jar.asc, false"
  })
  @DisplayName(
      "tells the .asc signature of a maven-metadata.xml by its file name alone, at any level"
          + " (RPS-1185, RPS-1177)")
  void recognisesAMetadataSignature(final String fileName, final boolean expected) {
    assertThat(ArtifactUtils.isMetadataSignature(fileName)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0} is metadata-family: {1}")
  @CsvSource({
    "maven-metadata.xml, true",
    "MAVEN-METADATA.XML, true",
    "maven-metadata.xml.sha1, true",
    "maven-metadata.xml.SHA1, true",
    "maven-metadata.xml.md5, true",
    "maven-metadata.xml.asc, true",
    "maven-metadata.xml.asc.sha1, true",
    "lib-1.0.jar, false",
    "maven-metadata.xml-plugin-1.0.jar, false",
    "maven-metadata.xml-plugin-1.0.jar.sha1, false",
    "my-maven-metadata.xml, false"
  })
  @DisplayName(
      "tells the metadata family by the file name alone, not a substring of it (RPS-1177, same"
          + " shape of fix as RPS-1196)")
  void recognisesTheMetadataFamilyByFileNameAlone(final String fileName, final boolean expected) {
    assertThat(ArtifactUtils.isMetadataFamilyFile(fileName)).isEqualTo(expected);
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
    final var gav = ArtifactUtils.getGavByFile(StoragePath.of(UUID.randomUUID(), path));

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

    final var gav = ArtifactUtils.getGavByFile(StoragePath.of(UUID.randomUUID(), path));

    assertThat(gav).isNotNull();
    assertThat(gav.getGroupId()).isEqualTo("com.acme");
    assertThat(gav.getArtifactId()).isEqualTo("maven-metadata.xml-plugin");
    assertThat(gav.getVersion()).isEqualTo("1.0");
    assertThat(gav.getExtension()).isEqualTo("jar");
  }

  @ParameterizedTest(name = "{0} is a checksum file: {1}")
  @CsvSource({
    "lib-1.0.jar.sha1, true",
    "lib-1.0.jar.md5, true",
    "lib-1.0.jar.sha256, true",
    "lib-1.0.jar.sha512, true",
    "lib-1.0.jar.SHA1, false",
    "lib-1.0.jar.Md5, false",
    "lib-1.0.jar, false",
    "maven-metadata.xml.sha1, true",
    "maven-metadata.xml.SHA1, false"
  })
  @DisplayName(
      "tells a checksum file by its suffix, case-sensitively like the M2 layout (RPS-1195)")
  void checksumSuffixIsMatchedCaseSensitively(final String fileName, final boolean expected) {
    assertThat(ArtifactUtils.isChecksumFile(fileName)).isEqualTo(expected);
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
    final var gav = ArtifactUtils.getGavByFile(StoragePath.of(UUID.randomUUID(), path));

    assertThat(gav).isNotNull();
    assertThat(gav.getGroupId()).isEqualTo(groupId);
    assertThat(gav.getArtifactId()).isEqualTo(artifactId);
    assertThat(gav.getVersion()).isEqualTo(version);
    assertThat(gav.getClassifier()).isEqualTo(classifier);
    assertThat(gav.getExtension()).isEqualTo(extension);
  }

  @Test
  @DisplayName("a metadata signature is neither a POM signature nor a POM to parse (RPS-1185)")
  void aMetadataSignatureIsNotAPomSignature() {
    final var storagePath =
        StoragePath.of(UUID.randomUUID(), "com/acme/lib/maven-metadata.xml.asc");

    assertThat(ArtifactUtils.isPomSignature(storagePath)).isFalse();
    assertThat(ArtifactUtils.isPomToParse(storagePath)).isFalse();
  }

  private static final String POM_PATH_OF_ACME_LIB = "com/acme/lib/1.0/lib-1.0.pom";

  /** A POM with the given elements in place of the coordinates, like a client would send it. */
  private static Model pomWith(final String elements) {
    final var pom =
        "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
            + elements
            + "</project>";

    return Objects.requireNonNull(
        ArtifactUtils.readModel(new ByteArrayInputStream(pom.getBytes(UTF_8))));
  }

  private static String parentOf(final String groupId) {
    return "<parent><groupId>"
        + groupId
        + "</groupId><artifactId>par</artifactId><version>1</version></parent>";
  }

  @Test
  @DisplayName("declares the groupId of the POM, else the one of its parent, else none")
  void declaredGroupIdFallsBackToTheParent() {
    assertThat(
            ArtifactUtils.declaredGroupId(
                pomWith("<groupId>com.acme</groupId>" + parentOf("org.parent"))))
        .isEqualTo("com.acme");
    assertThat(ArtifactUtils.declaredGroupId(pomWith(parentOf("org.parent"))))
        .isEqualTo("org.parent");
    assertThat(ArtifactUtils.declaredGroupId(pomWith("<artifactId>lib</artifactId>"))).isNull();
  }

  @ParameterizedTest(name = "a POM of {0} under com/acme is refused")
  @ValueSource(
      strings = {
        "<groupId>org.other</groupId><artifactId>lib</artifactId><version>1.0</version>",
        "<groupId>com.Acme</groupId><artifactId>lib</artifactId><version>1.0</version>",
        "<groupId>${g}</groupId><artifactId>lib</artifactId><version>1.0</version>",
        "<groupId></groupId><artifactId>lib</artifactId><version>1.0</version>",
        "<parent><groupId>org.other</groupId><artifactId>par</artifactId><version>1</version>"
            + "</parent><artifactId>lib</artifactId><version>1.0</version>",
        "<groupId>org.other</groupId>"
            + "<parent><groupId>com.acme</groupId>"
            + "<artifactId>par</artifactId><version>1</version></parent>"
            + "<artifactId>lib</artifactId><version>1.0</version>"
      })
  @DisplayName(
      "refuses a POM whose groupId is not the one of its path, with a fixed msgId (RPS-1193)")
  void refusesAPomWhoseGroupIdIsNotItsPaths(final String elements) {
    final var model = pomWith(elements);

    assertThatThrownBy(() -> ArtifactUtils.checkPomGroupIdMatchesPath(model, POM_PATH_OF_ACME_LIB))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("pomGroupIdMismatch");
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "com/acme/lib/1.0/lib-1.0.pom | <groupId>com.acme</groupId><artifactId>lib</artifactId>"
            + "<version>1.0</version>",
        "com/acme/lib/1.0/lib-1.0.pom | <parent><groupId>com.acme</groupId><artifactId>par</artifactId>"
            + "<version>1</version></parent><artifactId>lib</artifactId><version>1.0</version>",
        "com/acme/lib/1.0/lib-1.0.pom | <artifactId>lib</artifactId><version>1.0</version>",
        "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1.pom |"
            + " <groupId>com.acme</groupId><artifactId>lib</artifactId>"
            + "<version>1.0-SNAPSHOT</version>",
        "com/acme/lib/1.0/lib-1.0.pom | <groupId>com.acme</groupId><artifactId>lib</artifactId>"
            + "<version>${revision}</version>",
        "com/acme/lib/1.0/lib-1.0.pom | <groupId>com.acme</groupId><artifactId>other</artifactId>"
            + "<version>1.0</version>",
        "com/acme/lib/1.0/lib-1.0.pom | <groupId>com.acme</groupId><artifactId>lib</artifactId>"
            + "<version>1.0</version><packaging>pom</packaging>",
        "com/acme/lib/1.0/lib-1.0.pom | <groupId>com.acme</groupId><artifactId>lib</artifactId>"
            + "<version>1.0</version><packaging>maven-plugin</packaging>",
        "com/acme/lib/1.0/lib-1.0.pom | <groupId>com.acme</groupId><artifactId>lib</artifactId>"
            + "<version>1.0</version><distributionManagement><relocation><groupId>com.new</groupId>"
            + "</relocation></distributionManagement>",
        "com/acme/sub/lib/1.0/lib-1.0.pom | <groupId>com.acme.sub</groupId>"
            + "<artifactId>lib</artifactId><version>1.0</version>"
      })
  @DisplayName("accepts the POMs real clients send, whatever their artifactId and version")
  void acceptsThePomsRealClientsSend(final String path, final String elements) {
    ArtifactUtils.checkPomGroupIdMatchesPath(pomWith(elements), path);
  }

  @Test
  @DisplayName("does not check a POM that has no model or a path that has no GAV")
  void skipsThePathCheckWhenThePathHasNoGav() {
    final var mismatching =
        pomWith("<groupId>org.other</groupId><artifactId>lib</artifactId><version>1.0</version>");

    ArtifactUtils.checkPomGroupIdMatchesPath(mismatching, "io/stray.pom");
    ArtifactUtils.checkPomGroupIdMatchesPath(mismatching, "com/acme/lib/1.0/other-1.0.pom");
    ArtifactUtils.checkPomGroupIdMatchesPath(null, POM_PATH_OF_ACME_LIB);
  }

  @ParameterizedTest(name = "{0} is an artifact signature: {1}")
  @CsvSource({
    "com/acme/lib/1.0/lib-1.0.pom.asc, true",
    "com/acme/lib/1.0/lib-1.0.jar.asc, true",
    "com/acme/lib/1.0/lib-1.0-sources.jar.asc, true",
    "com/acme/lib/1.0/lib-1.0.module.asc, true",
    "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1.jar.asc, true",
    "com/acme/lib/1.0/lib-1.0.jar.asc.sha1, false",
    "com/acme/lib/1.0/lib-1.0.jar.ASC, false",
    "com/acme/lib/maven-metadata.xml.asc, false",
    "com/acme/lib/1.0-SNAPSHOT/maven-metadata.xml.asc, false",
    "com/acme/lib/1.0/lib-1.0.jar, false"
  })
  @DisplayName("tells the .asc signature of any artifact file by its file name alone (RPS-1188)")
  void recognisesAnArtifactSignature(final String path, final boolean expected) {
    final var storagePath = StoragePath.of(UUID.randomUUID(), path);

    assertThat(ArtifactUtils.isArtifactSignature(storagePath)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}, verify all {1}: {2}")
  @CsvSource({
    "com/acme/lib/1.0/lib-1.0.pom.asc, false, true",
    "com/acme/lib/1.0/lib-1.0.pom.asc, true, true",
    "com/acme/lib/1.0/lib-1.0.jar.asc, false, false",
    "com/acme/lib/1.0/lib-1.0.jar.asc, true, true",
    "com/acme/lib/maven-metadata.xml.asc, true, false",
    "com/acme/lib/1.0/lib-1.0.jar.asc.sha1, true, false",
    "com/acme/lib/1.0/lib-1.0.jar, true, false"
  })
  @DisplayName("a POM signature is always verified, any other one only when verifying all")
  void tellsWhichSignaturesAreVerified(
      final String path, final boolean verifyAll, final boolean expected) {
    final var storagePath = StoragePath.of(UUID.randomUUID(), path);

    assertThat(ArtifactUtils.isSignatureToVerify(storagePath, verifyAll)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0} is signable: {1}")
  @CsvSource({
    "lib-1.0.pom, true",
    "lib-1.0.jar, true",
    "lib-1.0-sources.jar, true",
    "lib-1.0-javadoc.jar, true",
    "lib-1.0.module, true",
    "lib-1.0.klib, true",
    "lib-1.0.tar.gz, true",
    "lib-1.0-kotlin-tooling-metadata.json, true",
    "lib-1.0.jar.asc, false",
    "lib-1.0.jar.ASC, false",
    "lib-1.0.jar.sha1, false",
    "lib-1.0.jar.md5, false",
    "lib-1.0.jar.sha256, false",
    "lib-1.0.jar.sha512, false",
    "lib-1.0.jar.asc.sha1, false",
    "maven-metadata.xml, false",
    "maven-metadata.xml.sha1, false",
    "maven-metadata.xml.asc, false"
  })
  @DisplayName("tells the files a signing tool signs: not a checksum, a signature or metadata")
  void recognisesASignableFile(final String fileName, final boolean expected) {
    assertThat(ArtifactUtils.isSignableFile(fileName)).isEqualTo(expected);
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

    assertThat(ArtifactUtils.filesToSign("com/acme/lib/1.0", files))
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

    assertThat(ArtifactUtils.filesToSign("com/acme/lib/1.0-SNAPSHOT", files))
        .containsExactlyInAnyOrder(
            "lib-1.0-20260921.101010-10.pom", "lib-1.0-20260921.101010-10.jar");
  }

  @Test
  @DisplayName("a timestamped snapshot build is newer than a literal SNAPSHOT file")
  void aTimestampedBuildBeatsALiteralSnapshotFile() {
    assertThat(
            ArtifactUtils.filesToSign(
                "com/acme/lib/1.0-SNAPSHOT",
                List.of("lib-1.0-SNAPSHOT.jar", "lib-1.0-20260921.101010-1.jar")))
        .containsExactly("lib-1.0-20260921.101010-1.jar");
    assertThat(
            ArtifactUtils.filesToSign(
                "com/acme/lib/1.0-SNAPSHOT",
                List.of("lib-1.0-SNAPSHOT.jar", "lib-1.0-SNAPSHOT.pom")))
        .containsExactlyInAnyOrder("lib-1.0-SNAPSHOT.jar", "lib-1.0-SNAPSHOT.pom");
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
    final var gav = ArtifactUtils.convertPathToGav(path);

    assertThat(gav).isNotNull();
    assertThat(ArtifactUtils.isNonUniqueSnapshotFile(gav)).isEqualTo(nonUnique);
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

    assertThat(ArtifactUtils.newestSnapshotPomName("lib", "1.0-SNAPSHOT", files))
        .isEqualTo("lib-1.0-20260921.101010-10.pom");
  }

  @Test
  @DisplayName("a literal POM is the answer when no timestamped POM is stored")
  void picksTheLiteralSnapshotPom() {
    assertThat(
            ArtifactUtils.newestSnapshotPomName(
                "lib", "1.0-SNAPSHOT", List.of("lib-1.0-SNAPSHOT.jar", "lib-1.0-SNAPSHOT.pom")))
        .isEqualTo("lib-1.0-SNAPSHOT.pom");
    assertThat(ArtifactUtils.newestSnapshotPomName("lib", "SNAPSHOT", List.of("lib-SNAPSHOT.pom")))
        .isEqualTo("lib-SNAPSHOT.pom");
  }

  @Test
  @DisplayName("no snapshot POM is answered for a release, a missing POM or another artifact")
  void picksNoSnapshotPom() {
    assertThat(ArtifactUtils.newestSnapshotPomName("lib", "1.0", List.of("lib-1.0.pom"))).isNull();
    assertThat(ArtifactUtils.newestSnapshotPomName("lib", "1.0-SNAPSHOT", List.of())).isNull();
    assertThat(
            ArtifactUtils.newestSnapshotPomName(
                "lib", "1.0-SNAPSHOT", List.of("lib-1.0-SNAPSHOT.jar", "other-1.0-SNAPSHOT.pom")))
        .isNull();
    assertThat(
            ArtifactUtils.newestSnapshotPomName(
                "l.b", "1.0-SNAPSHOT", List.of("lib-1.0-SNAPSHOT.pom")))
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

    assertThat(ArtifactUtils.versionDirFileNames("com/acme/lib/1.0", items))
        .containsExactly("lib-1.0.jar", "lib-1.0.jar");
  }

  private static StorageItemInfo item(
      final String name, final String path, final boolean directory) {
    return StorageItemInfo.builder().name(name).path(path).directory(directory).build();
  }
}
