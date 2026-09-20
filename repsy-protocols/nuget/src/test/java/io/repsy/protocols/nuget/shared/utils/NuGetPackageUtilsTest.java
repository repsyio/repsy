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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
