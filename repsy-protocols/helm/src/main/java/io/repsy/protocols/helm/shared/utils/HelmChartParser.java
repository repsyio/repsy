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

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartMetadata;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import lombok.experimental.UtilityClass;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jspecify.annotations.NullMarked;
import org.yaml.snakeyaml.Yaml;

/** Parses Chart.yaml from a .tgz stream without extracting to disk. */
@UtilityClass
@NullMarked
public class HelmChartParser {

  public static HelmChartMetadata parseChartYaml(final InputStream tgzStream) throws IOException {
    try (final var gzip = new GZIPInputStream(tgzStream);
        final var tar = new TarArchiveInputStream(gzip)) {

      var entry = tar.getNextEntry();
      while (entry != null) {
        if (!entry.isDirectory() && isChartYaml(entry.getName())) {
          return parseYaml(tar.readAllBytes());
        }
        entry = tar.getNextEntry();
      }
    }
    throw new BadRequestException("chartYamlNotFound");
  }

  private static boolean isChartYaml(final String entryName) {
    return entryName.equals(HelmConstants.CHART_YAML)
        || entryName.endsWith("/" + HelmConstants.CHART_YAML);
  }

  @SuppressWarnings("unchecked")
  private static HelmChartMetadata parseYaml(final byte[] bytes) {
    final var yaml = new Yaml();
    final Map<String, Object> parsed = yaml.load(new ByteArrayInputStream(bytes));

    final var name = (String) parsed.get("name");
    final var version = (String) parsed.get("version");

    if (name == null || name.isBlank()) {
      throw new BadRequestException("chartNameMissing");
    }
    if (version == null || version.isBlank()) {
      throw new BadRequestException("chartVersionMissing");
    }

    return HelmChartMetadata.builder()
        .name(name)
        .version(version)
        .description((String) parsed.get("description"))
        .appVersion((String) parsed.get("appVersion"))
        .type((String) parsed.get("type"))
        .build();
  }
}
