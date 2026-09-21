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
import io.repsy.libs.storage.core.dtos.StoragePath;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;
import org.apache.maven.artifact.repository.metadata.Metadata;
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
    return Objects.requireNonNull(ArtifactUtils.readMetadata(xml.getBytes(UTF_8)));
  }

  @Test
  @DisplayName(
      "plugin metadata needs at least one plugin, an empty plugin list is not plugin metadata")
  void pluginMetadataNeedsAtLeastOnePlugin() throws Exception {
    assertThat(ArtifactUtils.isPluginMetadata(metadata("<metadata/>"))).isFalse();
    assertThat(ArtifactUtils.isPluginMetadata(metadata(ARTIFACT_METADATA))).isFalse();
    assertThat(ArtifactUtils.isPluginMetadata(metadata(VERSION_METADATA))).isFalse();
    assertThat(ArtifactUtils.isPluginMetadata(metadata(GROUP_METADATA))).isTrue();
    assertThat(ArtifactUtils.isPluginMetadata(null)).isFalse();
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
    assertThat(ArtifactUtils.isVersionLevelMetadata(null)).isFalse();
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
    "com/example/lib/maven-metadata.xml, false"
  })
  @DisplayName("tells the POMs the artifact service parses from the files stored beside them")
  void recognisesThePomsToParse(final String path, final boolean expected) {
    final var storagePath = StoragePath.of(UUID.randomUUID(), path);

    assertThat(ArtifactUtils.isPomToParse(storagePath)).isEqualTo(expected);
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
            + " 1.0-SNAPSHOT, NULL, jar"
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
        "archetype-catalog.xml"
      })
  @DisplayName("finds no GAV for a path outside the layout")
  void noGavOutsideTheLayout(final String path) {
    assertThat(ArtifactUtils.getGavByFile(StoragePath.of(UUID.randomUUID(), path))).isNull();
  }
}
