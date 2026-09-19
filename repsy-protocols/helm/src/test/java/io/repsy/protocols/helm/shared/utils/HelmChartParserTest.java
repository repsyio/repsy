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
package io.repsy.protocols.helm.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HelmChartParserTest {

  private static final String BASE = "name: payments\nversion: 1.0.0\n";

  private static ByteArrayInputStream chart(final String chartYaml) throws IOException {
    final var bytes = new ByteArrayOutputStream();
    try (final var gzip = new GZIPOutputStream(bytes);
        final var tar = new TarArchiveOutputStream(gzip)) {
      final var data = chartYaml.getBytes(StandardCharsets.UTF_8);
      final var entry = new TarArchiveEntry("payments/Chart.yaml");
      entry.setSize(data.length);
      tar.putArchiveEntry(entry);
      tar.write(data);
      tar.closeArchiveEntry();
    }
    return new ByteArrayInputStream(bytes.toByteArray());
  }

  @Test
  void readsQuotedStringScalars() throws IOException {
    final var metadata =
        HelmChartParser.parseChartYaml(
            chart(BASE + "description: \"42\"\nappVersion: \"1.10\"\ntype: application\n"));

    assertThat(metadata.getName()).isEqualTo("payments");
    assertThat(metadata.getVersion()).isEqualTo("1.0.0");
    assertThat(metadata.getDescription()).isEqualTo("42");
    assertThat(metadata.getAppVersion()).isEqualTo("1.10");
    assertThat(metadata.getType()).isEqualTo("application");
  }

  @Test
  void optionalScalarsMayBeOmitted() throws IOException {
    final var metadata = HelmChartParser.parseChartYaml(chart(BASE));

    assertThat(metadata.getDescription()).isNull();
    assertThat(metadata.getAppVersion()).isNull();
    assertThat(metadata.getType()).isNull();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "appVersion: 2|chartAppVersionInvalid",
        "appVersion: 1.10|chartAppVersionInvalid",
        "appVersion: true|chartAppVersionInvalid",
        "appVersion: [1, 2]|chartAppVersionInvalid",
        "description: 42|chartDescriptionInvalid",
        "type: 3|chartTypeInvalid"
      })
  void rejectsNonStringOptionalScalars(final String line, final String msgId) {
    assertThatThrownBy(() -> HelmChartParser.parseChartYaml(chart(BASE + line + "\n")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(msgId);
  }

  @Test
  void rejectsNonStringName() {
    assertThatThrownBy(() -> HelmChartParser.parseChartYaml(chart("name: 5\nversion: 1.0.0\n")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("chartNameInvalid");
  }

  @ParameterizedTest
  @CsvSource({"2", "1.0"})
  void rejectsNonStringVersion(final String version) {
    assertThatThrownBy(
            () -> HelmChartParser.parseChartYaml(chart("name: payments\nversion: " + version)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("chartVersionInvalid");
  }

  @Test
  void rejectsMissingNameAndVersion() {
    assertThatThrownBy(() -> HelmChartParser.parseChartYaml(chart("version: 1.0.0\n")))
        .hasMessage("chartNameMissing");
    assertThatThrownBy(() -> HelmChartParser.parseChartYaml(chart("name: payments\n")))
        .hasMessage("chartVersionMissing");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {"''", "- a", "just a string", "name: [unclosed"})
  void rejectsDocumentsThatAreNotAMapping(final String chartYaml) {
    assertThatThrownBy(() -> HelmChartParser.parseChartYaml(chart(chartYaml + "\n")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("chartYamlInvalid");
  }
}
