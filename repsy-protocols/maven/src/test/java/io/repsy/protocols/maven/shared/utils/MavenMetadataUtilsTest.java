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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.repsy.core.error_handling.exceptions.BadRequestException;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@DisplayName("MavenMetadataUtils")
class MavenMetadataUtilsTest {

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
    return MavenMetadataUtils.readMetadata(xml.getBytes(UTF_8));
  }

  @Test
  @DisplayName(
      "plugin metadata needs at least one plugin, an empty plugin list is not plugin metadata")
  void pluginMetadataNeedsAtLeastOnePlugin() throws Exception {
    assertThat(MavenMetadataUtils.isPluginMetadata(metadata("<metadata/>"))).isFalse();
    assertThat(MavenMetadataUtils.isPluginMetadata(metadata(ARTIFACT_METADATA))).isFalse();
    assertThat(MavenMetadataUtils.isPluginMetadata(metadata(VERSION_METADATA))).isFalse();
    assertThat(MavenMetadataUtils.isPluginMetadata(metadata(GROUP_METADATA))).isTrue();
  }

  @Test
  @DisplayName("version-level metadata is recognised by the version it names")
  void versionLevelMetadataIsRecognisedByItsVersion() throws Exception {
    assertThat(MavenMetadataUtils.isVersionLevelMetadata(metadata(VERSION_METADATA))).isTrue();
    assertThat(MavenMetadataUtils.isVersionLevelMetadata(metadata(ARTIFACT_METADATA))).isFalse();
    assertThat(MavenMetadataUtils.isVersionLevelMetadata(metadata(GROUP_METADATA))).isFalse();
    assertThat(MavenMetadataUtils.isVersionLevelMetadata(metadata("<metadata/>"))).isFalse();
    assertThat(
            MavenMetadataUtils.isVersionLevelMetadata(
                metadata("<metadata><version> </version></metadata>")))
        .isFalse();
  }

  @Test
  @DisplayName("answers malformed metadata with a fixed msgId that does not echo the file")
  void malformedMetadataYieldsFixedMessageId() {
    final var secret = "do-not-reflect-this-marker";
    final var malformed = "<metadata><versioning><!-- " + secret + " -->";

    assertThatThrownBy(() -> MavenMetadataUtils.readMetadata(malformed.getBytes(UTF_8)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("malformedMetadataFile")
        .satisfies(e -> assertThat(e.toString()).doesNotContain(secret));
  }

  @Test
  @DisplayName("sorts the versions and takes latest as the highest, release as the highest release")
  void setsReleaseAndLatestFromTheSortedVersions() throws Exception {
    final var metadata =
        metadata(
            "<metadata><versioning><versions><version>2.0-SNAPSHOT</version>"
                + "<version>1.0</version><version>1.10</version><version>1.2</version>"
                + "</versions></versioning></metadata>");

    MavenMetadataUtils.setReleaseAndLatest(metadata);

    assertThat(metadata.getVersioning().getVersions())
        .containsExactly("1.0", "1.2", "1.10", "2.0-SNAPSHOT");
    assertThat(metadata.getVersioning().getLatest()).isEqualTo("2.0-SNAPSHOT");
    assertThat(metadata.getVersioning().getRelease()).isEqualTo("1.10");
  }

  @Test
  @DisplayName("leaves metadata without versioning or versions alone")
  void setReleaseAndLatestIgnoresMetadataWithoutVersions() throws Exception {
    final var none = metadata("<metadata/>");
    final var empty = metadata("<metadata><versioning/></metadata>");

    MavenMetadataUtils.setReleaseAndLatest(none);
    MavenMetadataUtils.setReleaseAndLatest(empty);

    assertThat(none.getVersioning()).isNull();
    assertThat(empty.getVersioning().getLatest()).isNull();
    assertThat(empty.getVersioning().getRelease()).isNull();
  }

  @Test
  @DisplayName("does not log the content of a malformed file (RPS-2174)")
  void malformedMetadataDoesNotLogTheContent() {
    final var logs = new ListAppender<ILoggingEvent>();
    final var logger = (Logger) LoggerFactory.getLogger(MavenMetadataUtils.class);
    logs.start();
    logger.addAppender(logs);

    try {
      assertThatThrownBy(
              () ->
                  MavenMetadataUtils.readMetadata(
                      "<metadata><s3cr3t-token-value></metadata>".getBytes(UTF_8)))
          .isInstanceOf(BadRequestException.class);
    } finally {
      logger.detachAppender(logs);
    }

    assertThat(logs.list).isNotEmpty();
    assertThat(logs.list)
        .noneMatch(event -> event.getFormattedMessage().contains("s3cr3t-token-value"));
  }
}
