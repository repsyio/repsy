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

import io.repsy.protocols.maven.shared.artifact.dtos.RegisteredPlugin;
import io.repsy.protocols.maven.shared.artifact.dtos.RegisteredVersion;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * RPS-1369: the artifact-level {@code maven-metadata.xml} of an artifact whose client stored none
 * is built from its registered versions, and only for the paths that are one. RPS-1438: the
 * group-level one is built from the registered plugins of the group.
 */
@DisplayName("ArtifactMetadataSynthesizer (RPS-1369, RPS-1438)")
class ArtifactMetadataSynthesizerTest {

  private static final Instant T1 = Instant.parse("2026-09-21T10:10:10Z");
  private static final Instant T2 = Instant.parse("2026-09-22T08:00:01Z");

  private static RegisteredVersion version(final String name, final Instant at) {
    return new RegisteredVersion(name, at);
  }

  private static List<RegisteredVersion> mixedVersions() {
    return List.of(
        version("1.10", T1), version("1.0", T1), version("2.0-SNAPSHOT", T2), version("1.2", T1));
  }

  @Test
  @DisplayName("parses the artifact-level file and its group and artifact")
  void parsesTheFile() {
    final var request = ArtifactMetadataSynthesizer.parse("/com/acme/lib/maven-metadata.xml");

    assertThat(request).isNotNull();
    assertThat(request.groupId()).isEqualTo("com.acme");
    assertThat(request.artifactId()).isEqualTo("lib");
    assertThat(request.checksumAlgorithm()).isNull();
    assertThat(request.fileName()).isEqualTo("maven-metadata.xml");
    assertThat(request.metadataPath()).isEqualTo("com/acme/lib/maven-metadata.xml");
  }

  @ParameterizedTest
  @ValueSource(strings = {"md5", "sha1", "sha256", "sha512"})
  @DisplayName("parses each checksum of the file")
  void parsesEachChecksum(final String algorithm) {
    final var request =
        ArtifactMetadataSynthesizer.parse("/com/acme/lib/maven-metadata.xml." + algorithm);

    assertThat(request).isNotNull();
    assertThat(request.checksumAlgorithm()).isEqualTo(algorithm);
    assertThat(request.fileName()).isEqualTo("maven-metadata.xml." + algorithm);
    assertThat(request.metadataPath()).isEqualTo("com/acme/lib/maven-metadata.xml");
  }

  @Test
  @DisplayName("takes a path without the leading slash, a one-segment group and a dotted artifact")
  void takesOtherShapes() {
    final var noSlash = ArtifactMetadataSynthesizer.parse("acme/lib/maven-metadata.xml");
    final var dotted = ArtifactMetadataSynthesizer.parse("/org/acme/lib.core/maven-metadata.xml");

    assertThat(noSlash).isEqualTo(new ArtifactMetadataSynthesizer.Request("acme", "lib", null));
    assertThat(dotted)
        .isEqualTo(new ArtifactMetadataSynthesizer.Request("org.acme", "lib.core", null));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/com/acme/lib/maven-metadata.xml.asc",
        "/com/acme/lib/maven-metadata.xml.asc.sha1",
        "/com/acme/lib/maven-metadata.xml.SHA1",
        "/com/acme/lib/MAVEN-METADATA.XML",
        "/com/acme/lib/maven-metadata.xml.sha3",
        "/com/acme/lib/maven-metadata.xml.",
        "/com/acme/lib/1.0-SNAPSHOT/maven-metadata.xml",
        "/com/acme/lib/1.0-SNAPSHOT/maven-metadata.xml.sha1",
        "/lib/maven-metadata.xml",
        "/maven-metadata.xml",
        "maven-metadata.xml",
        "//lib/maven-metadata.xml",
        "/com//lib/maven-metadata.xml",
        "/com/acme/lib/",
        "/com/acme/lib/1.0/lib-1.0.jar",
        "/com/acme/lib/lib-1.0.pom"
      })
  @DisplayName("does not parse any other path")
  void parsesNothingElse(final String path) {
    assertThat(ArtifactMetadataSynthesizer.parse(path)).isNull();
  }

  @Test
  @DisplayName("lists the versions sorted, with the latest, the release and the newest time")
  void listsTheVersions() {
    final var xml = new String(render(mixedVersions()), UTF_8);

    assertThat(xml)
        .contains("<groupId>com.acme</groupId>", "<artifactId>lib</artifactId>")
        .contains("<latest>2.0-SNAPSHOT</latest>", "<release>1.10</release>")
        .contains("<lastUpdated>20260922080001</lastUpdated>");

    final var metadata = ArtifactUtils.readMetadata(render(mixedVersions()));

    assertThat(metadata.getGroupId()).isEqualTo("com.acme");
    assertThat(metadata.getArtifactId()).isEqualTo("lib");
    assertThat(metadata.getVersioning().getVersions())
        .containsExactly("1.0", "1.2", "1.10", "2.0-SNAPSHOT");
    assertThat(metadata.getVersioning().getLatest()).isEqualTo("2.0-SNAPSHOT");
    assertThat(metadata.getVersioning().getRelease()).isEqualTo("1.10");
    assertThat(metadata.getVersioning().getLastUpdated()).isEqualTo("20260922080001");
  }

  @Test
  @DisplayName("has no release for an artifact that has only snapshots")
  void hasNoReleaseForSnapshotsOnly() {
    final var metadata =
        ArtifactUtils.readMetadata(
            ArtifactMetadataSynthesizer.metadataXml(
                "com.acme",
                "lib",
                List.of(version("1.0-SNAPSHOT", T1), version("1.1-SNAPSHOT", T2))));

    assertThat(metadata.getVersioning().getLatest()).isEqualTo("1.1-SNAPSHOT");
    assertThat(metadata.getVersioning().getRelease()).isNull();
  }

  @Test
  @DisplayName("lists a single version as both its latest and its release")
  void listsASingleVersion() {
    final var metadata =
        ArtifactUtils.readMetadata(
            ArtifactMetadataSynthesizer.metadataXml(
                "com.acme", "lib", List.of(version("1.0", T1))));

    assertThat(metadata.getVersioning().getVersions()).containsExactly("1.0");
    assertThat(metadata.getVersioning().getLatest()).isEqualTo("1.0");
    assertThat(metadata.getVersioning().getRelease()).isEqualTo("1.0");
  }

  @Test
  @DisplayName("writes the time in UTC and leaves it out when no version has one")
  void writesTheTime() {
    final var withTime =
        ArtifactMetadataSynthesizer.metadataXml(
            "g",
            "a",
            List.of(version("1.0", Instant.parse("2026-01-02T03:04:05Z")), version("0.9", null)));
    final var withoutTime =
        ArtifactMetadataSynthesizer.metadataXml(
            "g", "a", List.of(new RegisteredVersion("1.0", null)));

    assertThat(ArtifactUtils.readMetadata(withTime).getVersioning().getLastUpdated())
        .isEqualTo("20260102030405");
    assertThat(ArtifactUtils.readMetadata(withoutTime).getVersioning().getLastUpdated()).isNull();
    assertThat(new String(withoutTime, UTF_8)).doesNotContain("lastUpdated");
  }

  @Test
  @DisplayName("is the same bytes whatever the order of the versions, and on every call")
  void isStable() {
    final var shuffled = new ArrayList<>(mixedVersions());
    Collections.reverse(shuffled);

    final var first = render(mixedVersions());

    assertThat(render(shuffled)).isEqualTo(first);
    assertThat(render(mixedVersions())).isEqualTo(first);
  }

  @ParameterizedTest
  @ValueSource(strings = {"md5", "sha1", "sha256", "sha512"})
  @DisplayName("checksums are the lower-case hex of the bytes and nothing else")
  void checksums(final String algorithm) {
    final var xml = render(mixedVersions());

    final var expected =
        switch (algorithm) {
          case "md5" -> DigestUtils.md5Hex(xml);
          case "sha1" -> DigestUtils.sha1Hex(xml);
          case "sha256" -> DigestUtils.sha256Hex(xml);
          case "sha512" -> DigestUtils.sha512Hex(xml);
          case null, default -> throw new IllegalStateException(algorithm);
        };

    assertThat(ArtifactMetadataSynthesizer.checksum(xml, algorithm))
        .isEqualTo(expected.getBytes(UTF_8));
  }

  @Test
  @DisplayName("refuses a checksum algorithm it does not know")
  void refusesAnUnknownAlgorithm() {
    assertThatThrownBy(() -> ArtifactMetadataSynthesizer.checksum(new byte[0], "crc32"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("parses the group-level file, a one-segment group and a path without a slash")
  void parsesTheGroupLevelFile() {
    final var deep =
        ArtifactMetadataSynthesizer.parseGroupLevel("/com/acme/tools/maven-metadata.xml");
    final var single = ArtifactMetadataSynthesizer.parseGroupLevel("acme/maven-metadata.xml");

    assertThat(deep)
        .isEqualTo(new ArtifactMetadataSynthesizer.GroupRequest("com.acme.tools", null));
    assertThat(deep.fileName()).isEqualTo("maven-metadata.xml");
    assertThat(deep.metadataPath()).isEqualTo("com/acme/tools/maven-metadata.xml");
    assertThat(single).isEqualTo(new ArtifactMetadataSynthesizer.GroupRequest("acme", null));
  }

  @ParameterizedTest
  @ValueSource(strings = {"md5", "sha1", "sha256", "sha512"})
  @DisplayName("parses each checksum of the group-level file")
  void parsesEachGroupChecksum(final String algorithm) {
    final var request =
        ArtifactMetadataSynthesizer.parseGroupLevel("/com/acme/maven-metadata.xml." + algorithm);

    assertThat(request).isNotNull();
    assertThat(request.groupId()).isEqualTo("com.acme");
    assertThat(request.checksumAlgorithm()).isEqualTo(algorithm);
    assertThat(request.fileName()).isEqualTo("maven-metadata.xml." + algorithm);
    assertThat(request.metadataPath()).isEqualTo("com/acme/maven-metadata.xml");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/com/acme/maven-metadata.xml.asc",
        "/com/acme/maven-metadata.xml.asc.sha1",
        "/com/acme/maven-metadata.xml.SHA1",
        "/com/acme/MAVEN-METADATA.XML",
        "/com/acme/maven-metadata.xml.sha3",
        "/com/acme/1.0-SNAPSHOT/maven-metadata.xml",
        "/maven-metadata.xml",
        "maven-metadata.xml",
        "//maven-metadata.xml",
        "/com//acme/maven-metadata.xml",
        "/com/acme/",
        "/com/acme/lib-1.0.pom"
      })
  @DisplayName("does not parse any other path as a group-level file")
  void parsesNoGroupLevelFileElse(final String path) {
    assertThat(ArtifactMetadataSynthesizer.parseGroupLevel(path)).isNull();
  }

  @Test
  @DisplayName("writes the plugins in the shape Maven writes for a deployed plugin")
  void writesThePlugins() {
    final var xml = ArtifactMetadataSynthesizer.groupMetadataXml(plugins());

    assertThat(new String(xml, UTF_8))
        .isEqualTo(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata>
              <plugins>
                <plugin>
                  <name>Hello Maven Plugin</name>
                  <prefix>hello</prefix>
                  <artifactId>hello-maven-plugin</artifactId>
                </plugin>
                <plugin>
                  <prefix>zed</prefix>
                  <artifactId>zed-maven-plugin</artifactId>
                </plugin>
              </plugins>
            </metadata>
            """);
  }

  @Test
  @DisplayName("leaves out the name when it is null or blank, and has no group, no versioning")
  void leavesOutTheName() {
    final var xml =
        new String(
            ArtifactMetadataSynthesizer.groupMetadataXml(
                List.of(
                    new RegisteredPlugin("a-maven-plugin", null, "a"),
                    new RegisteredPlugin("b-maven-plugin", "  ", "b"))),
            UTF_8);

    assertThat(xml).doesNotContain("<name>", "groupId", "versioning");

    final var metadata = ArtifactUtils.readMetadata(xml.getBytes(UTF_8));

    assertThat(metadata.getPlugins()).hasSize(2);
    assertThat(metadata.getPlugins().get(1).getPrefix()).isEqualTo("b");
  }

  @Test
  @DisplayName("lists the plugins in the order given and is the same bytes on every call")
  void groupFileIsStable() {
    final var first = ArtifactMetadataSynthesizer.groupMetadataXml(plugins());

    assertThat(ArtifactMetadataSynthesizer.groupMetadataXml(plugins())).isEqualTo(first);
    assertThat(
            ArtifactUtils.readMetadata(first).getPlugins().stream()
                .map(plugin -> plugin.getArtifactId())
                .toList())
        .containsExactly("hello-maven-plugin", "zed-maven-plugin");
    assertThat(ArtifactMetadataSynthesizer.checksum(first, "sha1"))
        .isEqualTo(DigestUtils.sha1Hex(first).getBytes(UTF_8));
  }

  private static List<RegisteredPlugin> plugins() {
    return List.of(
        new RegisteredPlugin("hello-maven-plugin", "Hello Maven Plugin", "hello"),
        new RegisteredPlugin("zed-maven-plugin", null, "zed"));
  }

  private static byte[] render(final List<RegisteredVersion> versions) {
    return ArtifactMetadataSynthesizer.metadataXml("com.acme", "lib", versions);
  }
}
