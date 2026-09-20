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
package io.repsy.protocols.nuget.shared.utils;

import static io.repsy.protocols.nuget.NuGetTestContexts.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.repsy.protocols.nuget.shared.dtos.NuGetCatalogEntry;
import io.repsy.protocols.nuget.shared.dtos.NuGetRegistrationLeafItem;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetDependencyInfo;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class NuGetPackageUtilsTest {

  @TempDir Path tempDir;

  @ParameterizedTest
  @CsvSource({
    "1, 1.0.0",
    "1.0, 1.0.0",
    "1.2, 1.2.0",
    "1.2.3, 1.2.3",
    "1.0.0.0, 1.0.0",
    "1.2.3.4, 1.2.3.4",
    "1.0.0.5, 1.0.0.5",
    "1.0-Alpha, 1.0.0-alpha",
    "1.0.0-Alpha.1, 1.0.0-alpha.1",
    "1.0+Build, 1.0.0",
    "1.0.0.0+Build, 1.0.0",
    "1.2.3.4+Build, 1.2.3.4",
    "1.0-beta+Build, 1.0.0-beta",
    "1.0.0-rc.1+build.5, 1.0.0-rc.1",
    "1.0.0-beta+build-1, 1.0.0-beta",
    "1.0.0+a-b, 1.0.0",
    "1.0.0+a, 1.0.0",
    "1.0.0+b, 1.0.0",
  })
  @DisplayName("normalizes a version to its canonical three-part form, without build metadata")
  void normalizesVersion(final String raw, final String expected) {
    assertThat(NuGetPackageUtils.normalizeNuGetVersion(raw)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({
    "1.0.0, 1.0.0",
    "1.0-Alpha, 1.0.0-alpha",
    "1.0+Build, 1.0.0+build",
    "1.0.0.0+Build, 1.0.0+build",
    "1.0-beta+Build, 1.0.0-beta+build",
    "1.0.0+a-b, 1.0.0+a-b",
  })
  @DisplayName("keeps the build metadata in the legacy form versions were stored under")
  void legacyVersionKeepsBuildMetadata(final String raw, final String expected) {
    assertThat(NuGetPackageUtils.legacyNuGetVersion(raw)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({
    "1.0.0, false",
    "1.0.0-beta.1, false",
    "1.0.0+build, true",
    "1.0.0-beta+build, true",
  })
  @DisplayName("tells whether a version carries build metadata")
  void detectsBuildMetadata(final String version, final boolean expected) {
    assertThat(NuGetPackageUtils.hasBuildMetadata(version)).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"1", "1.0", "1.2.3", "1.2.3.4", "1.0-beta.1", "1.0+build", "1.0.0-rc.1+build.5"})
  @DisplayName("accepts one to four numeric parts, with optional pre-release and build")
  void acceptsValidVersions(final String version) throws IOException {
    final var nupkg = nupkg("Some.Package", version);

    final var metadata = NuGetPackageUtils.readNuspecMetadata(nupkg);

    assertThat(metadata.packageId()).isEqualTo("Some.Package");
    assertThat(metadata.version()).isEqualTo(NuGetPackageUtils.normalizeNuGetVersion(version));
  }

  @Test
  @DisplayName("drops the build metadata from a version read from a nuspec")
  void dropsBuildMetadataFromNuspecVersion() throws IOException {
    final var metadata =
        NuGetPackageUtils.readNuspecMetadata(nupkg("Some.Package", "1.0.0-rc.1+Build.5"));

    assertThat(metadata.version()).isEqualTo("1.0.0-rc.1");
  }

  @Test
  @DisplayName("normalizes a two-part version read from a nuspec to three parts")
  void normalizesTwoPartNuspecVersion() throws IOException {
    final var metadata = NuGetPackageUtils.readNuspecMetadata(nupkg("Some.Package", "1.0"));

    assertThat(metadata.version()).isEqualTo("1.0.0");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "one.two",
        "1.2.3.4.5",
        "1.",
        ".1",
        "1..2",
        "01.0",
        "1.00",
        "1.0-",
        "1.0+",
        "a.b.c"
      })
  @DisplayName("rejects a malformed version")
  void rejectsInvalidVersions(final String version) throws IOException {
    final var nupkg = nupkg("Some.Package", version);

    assertThatThrownBy(() -> NuGetPackageUtils.readNuspecMetadata(nupkg))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Invalid NuGet version format.");
  }

  @Test
  @DisplayName("rejects a version longer than the version column, naming the limit")
  void rejectsOverLongVersion() throws IOException {
    final var version = "1.0.0-" + "a".repeat(59);
    final var nupkg = nupkg("Some.Package", version);

    assertThat(version).hasSize(65);
    assertThatThrownBy(() -> NuGetPackageUtils.readNuspecMetadata(nupkg))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> {
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(e.getReason()).isEqualTo("NuGet version is longer than 64 characters.");
            });
  }

  @Test
  @DisplayName("accepts a version exactly as long as the version column")
  void acceptsVersionAtTheColumnLimit() throws IOException {
    final var version = "1.0.0-" + "a".repeat(58);

    final var metadata = NuGetPackageUtils.readNuspecMetadata(nupkg("Some.Package", version));

    assertThat(version).hasSize(64);
    assertThat(metadata.version()).isEqualTo(version);
  }

  @Test
  @DisplayName("measures the version without its build metadata, which is not stored")
  void ignoresBuildMetadataForTheVersionLength() throws IOException {
    final var version = "1.0.0+" + "a".repeat(100);

    final var metadata = NuGetPackageUtils.readNuspecMetadata(nupkg("Some.Package", version));

    assertThat(metadata.version()).isEqualTo("1.0.0");
  }

  @Nested
  @DisplayName("length-limited nuspec metadata (RPS-1005)")
  class LengthLimitedMetadata {

    private static String nuspec(final String metadataXml) {
      return "<package><metadata><id>Some.Package</id><version>1.0.0</version>%s</metadata></package>"
          .formatted(metadataXml);
    }

    @Test
    @DisplayName("cuts a title longer than the title column to the column length")
    void truncatesLongTitle() {
      final var xml = nuspec("<title>" + "t".repeat(600) + "</title>");

      assertThat(NuGetPackageUtils.extractTitle(xml)).isEqualTo("t".repeat(512));
    }

    @Test
    @DisplayName("keeps a title exactly at the column length")
    void keepsTitleAtLimit() {
      final var title = "t".repeat(512);

      assertThat(NuGetPackageUtils.extractTitle(nuspec("<title>" + title + "</title>")))
          .isEqualTo(title);
    }

    @Test
    @DisplayName("does not split a surrogate pair when it cuts a title")
    void truncatesOnACodePointBoundary() {
      final var emoji = "😀";
      final var xml = nuspec("<title>" + emoji.repeat(600) + "</title>");

      final var title = NuGetPackageUtils.extractTitle(xml);

      assertThat(title).isEqualTo(emoji.repeat(512));
      assertThat(title.codePointCount(0, title.length())).isEqualTo(512);
    }

    @Test
    @DisplayName("counts characters, not UTF-16 units, so a title that fits is kept whole")
    void keepsAnAstralTitleThatFits() {
      final var title = "😀".repeat(512);

      assertThat(NuGetPackageUtils.extractTitle(nuspec("<title>" + title + "</title>")))
          .isEqualTo(title);
    }

    @Test
    @DisplayName("cuts tags longer than the tags column to the column length")
    void truncatesLongTags() {
      final var xml = nuspec("<tags>" + "g".repeat(1100) + "</tags>");

      assertThat(NuGetPackageUtils.extractTags(xml)).isEqualTo("g".repeat(1024));
    }

    @Test
    @DisplayName("keeps tags exactly at the column length")
    void keepsTagsAtLimit() {
      final var tags = "g".repeat(1024);

      assertThat(NuGetPackageUtils.extractTags(nuspec("<tags>" + tags + "</tags>")))
          .isEqualTo(tags);
    }

    @Test
    @DisplayName("returns null for a title and tags the nuspec does not declare")
    void missingTitleAndTags() {
      assertThat(NuGetPackageUtils.extractTitle(nuspec(""))).isNull();
      assertThat(NuGetPackageUtils.extractTags(nuspec(""))).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"iconUrl", "licenseUrl", "projectUrl"})
    @DisplayName("drops a URL longer than its column instead of cutting it")
    void dropsLongUrl(final String tag) {
      final var xml =
          nuspec("<%s>https://example.test/%s</%s>".formatted(tag, "a".repeat(600), tag));

      assertThat(NuGetPackageUtils.extractUrl(xml, tag)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"iconUrl", "licenseUrl", "projectUrl"})
    @DisplayName("keeps a URL exactly at the column length")
    void keepsUrlAtLimit(final String tag) {
      final var url = "https://example.test/" + "a".repeat(512 - 21);
      final var xml = nuspec("<%s>%s</%s>".formatted(tag, url, tag));

      assertThat(url).hasSize(512);
      assertThat(NuGetPackageUtils.extractUrl(xml, tag)).isEqualTo(url);
    }

    @Test
    @DisplayName("returns null for a URL the nuspec does not declare")
    void missingUrl() {
      assertThat(NuGetPackageUtils.extractUrl(nuspec(""), "projectUrl")).isNull();
    }
  }

  @ParameterizedTest
  @CsvSource({
    "/v3/package/Some.Package/1.0.0, Some.Package, 1.0.0",
    "/v3/package/Some.Package/1.0.0/, Some.Package, 1.0.0",
    "/v3/package/Some.Package/, Some.Package, ''",
    "/v3/package/Some.Package, Some.Package, ''",
    "/v3/package, '', ''"
  })
  @DisplayName("extracts the package id and version from the request path")
  void extractsPackageIdAndVersion(
      final String path, final String expectedId, final String expectedVersion) {
    final var ctx = context(path);

    assertThat(NuGetPackageUtils.extractPackageId(ctx)).isEqualTo(expectedId);
    final var idAndVersion = NuGetPackageUtils.extractPackageIdAndVersion(ctx);
    assertThat(idAndVersion.id()).isEqualTo(expectedId);
    assertThat(idAndVersion.version()).isEqualTo(expectedVersion);
  }

  @ParameterizedTest
  @CsvSource({
    "1.0.0.5, 1.0.0.6",
    "1.0.0.5, 1.0.0.10",
    "1.0.0, 1.0.0.1",
    "1.0.0.1, 1.0.1",
    "1.9.9.9, 2.0.0",
    "1.0.0.5-beta, 1.0.0.5",
    "1.0.0-beta, 1.0.0.1-alpha",
    "1.0.0.5-alpha, 1.0.0.5-beta",
    "1.0.0-alpha, 1.0.0",
    "1.0.0-beta.2, 1.0.0-beta.11",
    "1.0.0-Alpha, 1.0.0-beta",
    "1.2, 1.10",
    "2, 10.0.0",
  })
  @DisplayName("orders the first version before the second")
  void ordersVersions(final String lower, final String higher) {
    assertThat(NuGetPackageUtils.compareVersions(lower, higher)).isNegative();
    assertThat(NuGetPackageUtils.compareVersions(higher, lower)).isPositive();
  }

  @ParameterizedTest
  @CsvSource({
    "1.0, 1.0.0",
    "1.0.0.0, 1.0.0",
    "1.0.0+a, 1.0.0+b",
    "1.0.0.5, 1.0.0.5+build",
    "1.0.0-Beta, 1.0.0-beta",
  })
  @DisplayName("treats versions NuGet considers the same as equal")
  void comparesEqualVersions(final String first, final String second) {
    assertThat(NuGetPackageUtils.compareVersions(first, second)).isZero();
    assertThat(NuGetPackageUtils.compareVersions(second, first)).isZero();
  }

  @ParameterizedTest
  @CsvSource({"a.b.c, 1.0.0", "1.0.0, a.b.c", "1.2.3.4.5, 1.2.3.4", ".1, 1.0.0", "'', 1.0.0"})
  @DisplayName("falls back to a case-insensitive string comparison for an unparseable version")
  void fallsBackForUnparseableVersion(final String first, final String second) {
    assertThat(NuGetPackageUtils.compareVersions(first, second))
        .isEqualTo(first.compareToIgnoreCase(second));
  }

  @Test
  @DisplayName("bounds a registration page by its lowest and highest four-part versions")
  void boundsRegistrationPageByFourPartVersions() {
    final var leaves =
        Stream.of("1.0.0.10", "1.0.0.5", "1.0.0.6", "1.0.0")
            .map(NuGetPackageUtilsTest::leafItem)
            .toList();

    final var pages = NuGetPackageUtils.buildRegistrationPages(leaves, "https://x/index.json");

    assertThat(pages)
        .singleElement()
        .satisfies(
            page -> {
              assertThat(page.lower()).isEqualTo("1.0.0");
              assertThat(page.upper()).isEqualTo("1.0.0.10");
            });
  }

  @Nested
  @DisplayName("stored dependencies (RPS-1015)")
  class StoredDependencies {

    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
    private final Logger utilsLogger = (Logger) LoggerFactory.getLogger(NuGetPackageUtils.class);

    @BeforeEach
    void captureLogs() {
      this.logEvents.start();
      this.utilsLogger.addAppender(this.logEvents);
    }

    @AfterEach
    void releaseLogs() {
      this.utilsLogger.detachAppender(this.logEvents);
      this.logEvents.stop();
    }

    private List<String> warnings() {
      return this.logEvents.list.stream()
          .filter(event -> event.getLevel() == Level.WARN)
          .map(ILoggingEvent::getFormattedMessage)
          .toList();
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "<ArrayList><item><packageId>Legacy</packageId></item></ArrayList>",
          "[{\"packageId\": \"Cut.Off\"",
          "{\"packageId\": \"Not.An.Array\"}",
          "not json"
        })
    @DisplayName(
        "warns with the package id and version, not the value, and returns no dependencies")
    void warnsForUnreadableValue(final String stored) {
      assertThat(NuGetPackageUtils.parseDependenciesJson(stored, "Some.Package", "1.2.3"))
          .isEmpty();

      assertThat(this.warnings())
          .singleElement()
          .asString()
          .contains("Some.Package", "1.2.3")
          .doesNotContain(stored);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "null"})
    @DisplayName("treats a missing, blank or JSON null value as no dependencies without a warning")
    void treatsAbsentValueAsNoDependencies(final String stored) {
      assertThat(NuGetPackageUtils.parseDependenciesJson(stored, "Some.Package", "1.2.3"))
          .isEmpty();
      assertThat(NuGetPackageUtils.parseDependenciesJson(null, "Some.Package", "1.2.3")).isEmpty();

      assertThat(this.warnings()).isEmpty();
    }

    @Test
    @DisplayName("reads stored dependencies without a warning")
    void readsValidValue() {
      final var dependencies = List.of(new NuGetDependencyInfo("Serilog", "3.1.1", "net8.0"));

      assertThat(
              NuGetPackageUtils.parseDependenciesJson(
                  NuGetPackageUtils.toDependenciesJson(dependencies), "Some.Package", "1.2.3"))
          .isEqualTo(dependencies);

      assertThat(this.warnings()).isEmpty();
    }
  }

  private static NuGetRegistrationLeafItem leafItem(final String version) {
    final var entry =
        new NuGetCatalogEntry(
            "id",
            "type",
            "Some.Package",
            version,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            true,
            Instant.EPOCH,
            null);
    return new NuGetRegistrationLeafItem(
        "id", "type", entry, true, "content", Instant.EPOCH, "registration");
  }

  private Path nupkg(final String id, final String version) throws IOException {
    final var file = Files.createTempFile(tempDir, "pkg", ".nupkg");
    final var nuspec =
        "<package><metadata><id>%s</id><version>%s</version></metadata></package>"
            .formatted(id, version);

    try (final OutputStream out = Files.newOutputStream(file);
        final var zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry(id + ".nuspec"));
      zip.write(nuspec.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return file;
  }
}
