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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import java.nio.charset.StandardCharsets;
import org.apache.maven.model.Model;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Maven publish length guards (RPS-1138)")
class MavenPublishLimitsTest {

  private static Model model(final String elements) {
    final var pom =
        "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>a</artifactId>"
            + "<version>1</version>"
            + elements
            + "</project>";

    return ArtifactUtils.readModel(
        new java.io.ByteArrayInputStream(pom.getBytes(StandardCharsets.UTF_8)));
  }

  private static org.apache.maven.index.artifact.Gav gav(final String path) {
    return ArtifactUtils.convertPathToGav(path);
  }

  @Test
  @DisplayName("accepts coordinates exactly as long as their columns")
  void acceptsCoordinatesAtTheLimit() {
    final var artifact = "a".repeat(255);
    final var version = "1".repeat(255);
    final var path = "g/" + artifact + "/" + version + "/" + artifact + "-" + version + ".pom";
    final var group = "g".repeat(255) + "/a/1/a-1.pom";

    assertThatCode(() -> MavenPublishLimits.checkCoordinates(gav(path))).doesNotThrowAnyException();
    assertThatCode(() -> MavenPublishLimits.checkCoordinates(gav(group)))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a snapshot is measured by its base version, not by its timestamped file version")
  void measuresASnapshotByItsBaseVersion() {
    final var base = "1".repeat(240) + "-SNAPSHOT";
    final var path = "g/a/" + base + "/a-" + "1".repeat(240) + "-20260921.101010-1.pom";

    assertThatCode(() -> MavenPublishLimits.checkCoordinates(gav(path))).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("refuses an over-long groupId, artifactId and version by name")
  void refusesOverLongCoordinates() {
    final var tooLong = "a".repeat(256);

    assertThatThrownBy(() -> MavenPublishLimits.checkCoordinates(gav(tooLong + "/a/1/a-1.pom")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("groupIdTooLong");
    assertThatThrownBy(
            () ->
                MavenPublishLimits.checkCoordinates(
                    gav("g/" + tooLong + "/1/" + tooLong + "-1.pom")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("artifactIdTooLong");
    assertThatThrownBy(
            () ->
                MavenPublishLimits.checkCoordinates(
                    gav("g/a/" + tooLong + "/a-" + tooLong + ".pom")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("mavenVersionTooLong");
  }

  @Test
  @DisplayName("refuses a packaging longer than 50 characters and accepts one of 50 or none")
  void checksThePackaging() {
    assertThatThrownBy(
            () ->
                MavenPublishLimits.checkPackaging(
                    model("<packaging>" + "p".repeat(51) + "</packaging>")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("pomPackagingTooLong");
    assertThatCode(
            () ->
                MavenPublishLimits.checkPackaging(
                    model("<packaging>" + "p".repeat(50) + "</packaging>")))
        .doesNotThrowAnyException();
    assertThatCode(() -> MavenPublishLimits.checkPackaging(model(""))).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("keeps every value that fits, and every entry of a list that fits")
  void keepsWhatFits() {
    final var model =
        model(
            "<name>n</name><url>u</url>"
                + "<organization><name>o</name></organization><scm><url>s</url></scm>"
                + "<parent><groupId>g</groupId><artifactId>pa</artifactId><version>1</version></parent>"
                + "<licenses><license><name>l</name><url>lu</url></license></licenses>"
                + "<developers><developer><name>d</name><email>e</email></developer></developers>");

    MavenPublishLimits.dropOverLongFields(model);

    assertThat(model.getName()).isEqualTo("n");
    assertThat(model.getUrl()).isEqualTo("u");
    assertThat(model.getOrganization().getName()).isEqualTo("o");
    assertThat(model.getScm().getUrl()).isEqualTo("s");
    assertThat(model.getParent()).isNotNull();
    assertThat(model.getLicenses()).singleElement().extracting("url").isEqualTo("lu");
    assertThat(model.getDevelopers()).singleElement().extracting("email").isEqualTo("e");
  }

  @Test
  @DisplayName("tolerates a POM without organization, scm, parent, licenses or developers")
  void toleratesAnEmptyPom() {
    final var model = model("");

    MavenPublishLimits.dropOverLongFields(model);

    assertThat(model.getOrganization()).isNull();
    assertThat(model.getScm()).isNull();
    assertThat(model.getParent()).isNull();
    assertThat(model.getLicenses()).isEmpty();
    assertThat(model.getDevelopers()).isEmpty();
  }

  @ParameterizedTest(name = "a parent with an over-long {0} is dropped whole")
  @ValueSource(strings = {"groupId", "artifactId", "version"})
  @DisplayName("drops the parent as a whole when one of its coordinates is over-long")
  void dropsTheWholeParent(final String field) {
    final var long256 = "x".repeat(256);
    final var group = "groupId".equals(field) ? long256 : "g";
    final var artifact = "artifactId".equals(field) ? long256 : "pa";
    final var version = "version".equals(field) ? long256 : "1";
    final var model =
        model(
            "<parent><groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version></parent>"
                .formatted(group, artifact, version));

    MavenPublishLimits.dropOverLongFields(model);

    assertThat(model.getParent()).isNull();
  }

  @Test
  @DisplayName("drops an over-long name, url, organization, scm url and packaging")
  void dropsOverLongScalars() {
    final var long256 = "x".repeat(256);
    final var model =
        model(
            "<name>%1$s</name><url>%1$s</url><organization><name>%2$s</name></organization>"
                    .formatted(long256, "x".repeat(151))
                + "<scm><url>%s</url></scm><packaging>%s</packaging>"
                    .formatted(long256, "p".repeat(51)));

    MavenPublishLimits.dropOverLongFields(model);

    assertThat(model.getName()).isNull();
    assertThat(model.getUrl()).isNull();
    assertThat(model.getOrganization().getName()).isNull();
    assertThat(model.getScm().getUrl()).isNull();
    assertThat(model.getPackaging()).isNull();
  }

  @Test
  @DisplayName(
      "drops a license or developer without a usable name, and nulls an over-long url or email")
  void dropsUnusableListEntries() {
    final var long256 = "x".repeat(256);
    final var model =
        model(
            "<licenses><license><name>%1$s</name></license><license><url>u</url></license>"
                    .formatted(long256)
                + "<license><name>ok</name><url>%s</url></license></licenses>".formatted(long256)
                + "<developers><developer><name>%1$s</name></developer>".formatted(long256)
                + "<developer><email>e</email></developer>"
                + "<developer><name>ok</name><email>%s</email></developer></developers>"
                    .formatted(long256));

    MavenPublishLimits.dropOverLongFields(model);

    assertThat(model.getLicenses())
        .singleElement()
        .satisfies(
            license -> {
              assertThat(license.getName()).isEqualTo("ok");
              assertThat(license.getUrl()).isNull();
            });
    assertThat(model.getDevelopers())
        .singleElement()
        .satisfies(
            developer -> {
              assertThat(developer.getName()).isEqualTo("ok");
              assertThat(developer.getEmail()).isNull();
            });
  }

  @Test
  @DisplayName("dropIfTooLong keeps null, a value at the limit, and drops one past it")
  void dropIfTooLong() {
    assertThat(MavenPublishLimits.dropIfTooLong(null, 3)).isNull();
    assertThat(MavenPublishLimits.dropIfTooLong("abc", 3)).isEqualTo("abc");
    assertThat(MavenPublishLimits.dropIfTooLong("abcd", 3)).isNull();
  }
}
