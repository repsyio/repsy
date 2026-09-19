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
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import lombok.experimental.UtilityClass;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/** Parses Chart.yaml from a .tgz stream without extracting to disk. */
@UtilityClass
@NullMarked
public class HelmChartParser {

  private static final Pattern CHART_NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]*$");
  private static final Pattern SEMVER_PATTERN =
      Pattern.compile(
          "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
              + "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))"
              + "?(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$");

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

  private static HelmChartMetadata parseYaml(final byte[] bytes) {
    final var parsed = loadYaml(bytes);
    final var name = validateName(stringField(parsed, "name", "chartNameInvalid"));
    final var version = validateVersion(stringField(parsed, "version", "chartVersionInvalid"));
    return HelmChartMetadata.builder()
        .name(name)
        .version(version)
        .description(stringField(parsed, "description", "chartDescriptionInvalid"))
        .appVersion(stringField(parsed, "appVersion", "chartAppVersionInvalid"))
        .type(stringField(parsed, "type", "chartTypeInvalid"))
        .build();
  }

  private static Map<?, ?> loadYaml(final byte[] bytes) {
    final Object parsed;
    try {
      parsed =
          new Yaml(new SafeConstructor(new LoaderOptions())).load(new ByteArrayInputStream(bytes));
    } catch (final YAMLException e) {
      throw new BadRequestException("chartYamlInvalid");
    }
    if (!(parsed instanceof Map<?, ?> map)) {
      throw new BadRequestException("chartYamlInvalid");
    }
    return map;
  }

  /**
   * Reads a scalar that Helm declares as a string. YAML types an unquoted {@code 2} or {@code 1.10}
   * as a number, and coercing it would silently change the value ({@code 1.10} becomes {@code
   * 1.1}), so anything but a string is rejected, as Helm itself does.
   */
  private static @Nullable String stringField(
      final Map<?, ?> parsed, final String key, final String errorKey) {
    final var value = parsed.get(key);
    if (value != null && !(value instanceof String)) {
      throw new BadRequestException(errorKey);
    }
    return (String) value;
  }

  private static String validateName(final String name) {
    if (name == null || name.isBlank()) {
      throw new BadRequestException("chartNameMissing");
    }
    if (!CHART_NAME_PATTERN.matcher(name).matches()) {
      throw new BadRequestException("chartNameInvalid");
    }
    return name;
  }

  private static String validateVersion(final String version) {
    if (version == null || version.isBlank()) {
      throw new BadRequestException("chartVersionMissing");
    }
    if (!SEMVER_PATTERN.matcher(version).matches()) {
      throw new BadRequestException("chartVersionInvalid");
    }
    return version;
  }
}
