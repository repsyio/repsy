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
package io.repsy.os.server.protocols.nuget.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.protocols.nuget.shared.utils.NuGetPackageUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("NuGetPackageUtils nuspec metadata (RPS-945)")
class NuGetPackageUtilsTest {

  private static String nuspec(final String metadataXml) {
    return """
        <?xml version="1.0" encoding="utf-8"?>
        <package xmlns="http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd">
          <metadata>
            <id>Fixture.Package</id>
            <version>1.0.0</version>
            %s
          </metadata>
        </package>
        """
        .formatted(metadataXml);
  }

  @Nested
  @DisplayName("extractRepositoryUrl")
  class ExtractRepositoryUrl {

    @Test
    @DisplayName("reads the url attribute of the standard repository element")
    void attributeForm() {
      final var xml =
          nuspec("<repository type=\"git\" url=\"https://github.com/repsyio/repsy\" />");

      assertThat(NuGetPackageUtils.extractRepositoryUrl(xml))
          .isEqualTo("https://github.com/repsyio/repsy");
    }

    @Test
    @DisplayName("reads the url attribute when the element also has attributes and a body")
    void attributeFormWithMoreAttributes() {
      final var xml =
          nuspec(
              "<repository type=\"git\" url=\" https://example.test/r.git \" branch=\"main\""
                  + " commit=\"abc123\"></repository>");

      assertThat(NuGetPackageUtils.extractRepositoryUrl(xml))
          .isEqualTo("https://example.test/r.git");
    }

    @Test
    @DisplayName("falls back to the element text for the plain-text form")
    void plainTextForm() {
      final var xml = nuspec("<repository>https://example.test/plain.git</repository>");

      assertThat(NuGetPackageUtils.extractRepositoryUrl(xml))
          .isEqualTo("https://example.test/plain.git");
    }

    @Test
    @DisplayName("prefers the url attribute over the element text")
    void attributeWinsOverText() {
      final var xml = nuspec("<repository url=\"https://example.test/attr\">ignored</repository>");

      assertThat(NuGetPackageUtils.extractRepositoryUrl(xml))
          .isEqualTo("https://example.test/attr");
    }

    @Test
    @DisplayName("is null when the nuspec declares no repository")
    void absent() {
      assertThat(NuGetPackageUtils.extractRepositoryUrl(nuspec(""))).isNull();
    }

    @Test
    @DisplayName("is null for an empty repository element")
    void emptyElement() {
      assertThat(NuGetPackageUtils.extractRepositoryUrl(nuspec("<repository type=\"git\" />")))
          .isNull();
    }

    @Test
    @DisplayName("falls back to the plain-text form for a nuspec that is not well-formed XML")
    void malformedXml() {
      final var xml = "<package><metadata><repository>https://example.test/x</repository>";

      assertThat(NuGetPackageUtils.extractRepositoryUrl(xml)).isEqualTo("https://example.test/x");
    }

    @Test
    @DisplayName("drops a URL longer than the repository_url column instead of failing the publish")
    void overlongUrl() {
      final var xml = nuspec("<repository url=\"https://example.test/" + "a".repeat(500) + "\" />");

      assertThat(NuGetPackageUtils.extractRepositoryUrl(xml)).isNull();
    }

    @Test
    @DisplayName("keeps a URL exactly at the column length")
    void urlAtLimit() {
      final var url = "https://example.test/" + "a".repeat(512 - 21);
      final var xml = nuspec("<repository url=\"" + url + "\" />");

      assertThat(NuGetPackageUtils.extractRepositoryUrl(xml)).isEqualTo(url);
    }

    @Test
    @DisplayName("does not resolve a DOCTYPE, so external entities are never read")
    void doctypeIsRejected() {
      final var xml =
          """
          <?xml version="1.0"?>
          <!DOCTYPE package [<!ENTITY xxe SYSTEM "file:///etc/hostname">]>
          <package><metadata><repository url="&xxe;" /></metadata></package>
          """;

      assertThat(NuGetPackageUtils.extractRepositoryUrl(xml)).isNull();
    }
  }

  @Nested
  @DisplayName("extractReadme")
  class ExtractReadme {

    @TempDir Path tempDir;

    private Path nupkg(final Map<String, byte[]> entries) throws IOException {
      final var out = new ByteArrayOutputStream();
      try (final var zip = new ZipOutputStream(out)) {
        for (final var entry : entries.entrySet()) {
          zip.putNextEntry(new ZipEntry(entry.getKey()));
          zip.write(entry.getValue());
          zip.closeEntry();
        }
      }
      return Files.write(this.tempDir.resolve("fixture.nupkg"), out.toByteArray());
    }

    private static Map<String, byte[]> entries(final String name, final byte[] content) {
      final var entries = new LinkedHashMap<String, byte[]>();
      entries.put("Fixture.Package.nuspec", new byte[0]);
      entries.put(name, content);
      return entries;
    }

    private static byte[] utf8(final String text) {
      return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("reads the file the nuspec readme element points at")
    void readsDeclaredFile() throws IOException {
      final var xml = nuspec("<readme>docs/README.md</readme>");
      final var nupkg = this.nupkg(entries("docs/README.md", utf8("# Fixture\n\nHello")));

      assertThat(NuGetPackageUtils.extractReadme(nupkg, xml)).isEqualTo("# Fixture\n\nHello");
    }

    @Test
    @DisplayName("matches a backslash separated nuspec path against the archive entry")
    void backslashSeparators() throws IOException {
      final var xml = nuspec("<readme>docs\\README.md</readme>");
      final var nupkg = this.nupkg(entries("docs/README.md", utf8("readme")));

      assertThat(NuGetPackageUtils.extractReadme(nupkg, xml)).isEqualTo("readme");
    }

    @Test
    @DisplayName("matches a percent-encoded archive entry name")
    void percentEncodedEntry() throws IOException {
      final var xml = nuspec("<readme>docs/My Readme.md</readme>");
      final var nupkg = this.nupkg(entries("docs/My%20Readme.md", utf8("spaced")));

      assertThat(NuGetPackageUtils.extractReadme(nupkg, xml)).isEqualTo("spaced");
    }

    @Test
    @DisplayName("matches case-insensitively and ignores a leading slash")
    void caseAndLeadingSlash() throws IOException {
      final var xml = nuspec("<readme>/README.MD</readme>");
      final var nupkg = this.nupkg(entries("readme.md", utf8("case")));

      assertThat(NuGetPackageUtils.extractReadme(nupkg, xml)).isEqualTo("case");
    }

    @Test
    @DisplayName("drops a UTF-8 byte order mark")
    void dropsBom() throws IOException {
      final var xml = nuspec("<readme>README.md</readme>");
      final var nupkg = this.nupkg(entries("README.md", utf8("﻿with bom")));

      assertThat(NuGetPackageUtils.extractReadme(nupkg, xml)).isEqualTo("with bom");
    }

    @Test
    @DisplayName("is null when the nuspec declares no readme")
    void noReadmeElement() throws IOException {
      final var nupkg = this.nupkg(entries("README.md", utf8("not declared")));

      assertThat(NuGetPackageUtils.extractReadme(nupkg, nuspec(""))).isNull();
    }

    @Test
    @DisplayName("is null when the declared file is not in the package")
    void declaredFileMissing() throws IOException {
      final var xml = nuspec("<readme>docs/README.md</readme>");
      final var nupkg = this.nupkg(entries("lib/net8.0/Fixture.dll", utf8("MZ")));

      assertThat(NuGetPackageUtils.extractReadme(nupkg, xml)).isNull();
    }

    @Test
    @DisplayName("is null for a file over the size limit")
    void tooLarge() throws IOException {
      final var xml = nuspec("<readme>README.md</readme>");
      final var nupkg = this.nupkg(entries("README.md", new byte[256 * 1024 + 1]));

      assertThat(NuGetPackageUtils.extractReadme(nupkg, xml)).isNull();
    }

    @Test
    @DisplayName("keeps a file exactly at the size limit")
    void atLimit() throws IOException {
      final var xml = nuspec("<readme>README.md</readme>");
      final var content = "a".repeat(256 * 1024);
      final var nupkg = this.nupkg(entries("README.md", utf8(content)));

      assertThat(NuGetPackageUtils.extractReadme(nupkg, xml)).isEqualTo(content);
    }

    @Test
    @DisplayName("is null for a binary file, which PostgreSQL text columns cannot hold")
    void binaryFile() throws IOException {
      final var xml = nuspec("<readme>README.md</readme>");
      final var nupkg = this.nupkg(entries("README.md", new byte[] {'a', 0, 'b'}));

      assertThat(NuGetPackageUtils.extractReadme(nupkg, xml)).isNull();
    }

    @Test
    @DisplayName("is null instead of failing when the archive is unreadable")
    void unreadableArchive() throws IOException {
      final var broken = Files.write(this.tempDir.resolve("broken.nupkg"), utf8("not a zip"));

      assertThat(NuGetPackageUtils.extractReadme(broken, nuspec("<readme>README.md</readme>")))
          .isNull();
    }
  }
}
