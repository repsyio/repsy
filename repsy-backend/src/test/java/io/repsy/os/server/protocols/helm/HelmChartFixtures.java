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
package io.repsy.os.server.protocols.helm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

/** What the Helm integration tests share to build and push charts. */
public final class HelmChartFixtures {

  /** {@code POST} endpoint of a classic (ChartMuseum style) chart upload. */
  public static final String UPLOAD_PATH = "/{repo}/api/charts";

  public static final String OCI_MANIFEST_TYPE = "application/vnd.oci.image.manifest.v1+json";
  public static final String OCI_CONFIG_TYPE = "application/vnd.cncf.helm.config.v1+json";
  public static final String OCI_LAYER_TYPE = "application/vnd.cncf.helm.chart.content.v1.tar+gzip";

  private HelmChartFixtures() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * A {@code .tgz} whose {@code Chart.yaml} is exactly {@code chartYaml}. The entry is named after
   * a short directory, as a tar entry name is limited to 100 bytes and a chart name may be longer.
   */
  public static byte[] archive(final String chartYaml) {
    try {
      final var bytes = new ByteArrayOutputStream();
      try (final var gzip = new GZIPOutputStream(bytes);
          final var tar = new TarArchiveOutputStream(gzip)) {
        final var data = chartYaml.getBytes(StandardCharsets.UTF_8);
        final var entry = new TarArchiveEntry("chart/Chart.yaml");

        entry.setSize(data.length);
        tar.putArchiveEntry(entry);
        tar.write(data);
        tar.closeArchiveEntry();
      }

      return bytes.toByteArray();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A chart with only the required fields. */
  public static byte[] chart(final String name, final String version) {
    return archive(chartYaml(name, version, null, null));
  }

  /**
   * A chart with the length-limited fields given. The values are quoted, so an {@code appVersion}
   * such as {@code 1.10} stays a string; a null one is left out.
   */
  public static byte[] chart(
      final String name, final String version, final String appVersion, final String type) {
    return archive(chartYaml(name, version, appVersion, type));
  }

  /** The {@code Chart.yaml} of {@link #chart(String, String, String, String)}. */
  public static String chartYaml(
      final String name, final String version, final String appVersion, final String type) {
    final var yaml = new StringBuilder();

    yaml.append("apiVersion: v2\nname: \"%s\"\nversion: \"%s\"\n".formatted(name, version));
    if (appVersion != null) {
      yaml.append("appVersion: \"%s\"\n".formatted(appVersion));
    }
    if (type != null) {
      yaml.append("type: \"%s\"\n".formatted(type));
    }

    return yaml.toString();
  }

  /** A SemVer version of exactly {@code length} characters, such as {@code 1.0.0-aaaa}. */
  public static String versionOfLength(final int length) {
    final var prefix = "1.0.0-";

    return prefix + "a".repeat(length - prefix.length());
  }

  /**
   * The OCI digest of {@code bytes}, {@code algorithm} being {@code SHA-256} or {@code SHA-512}.
   */
  public static String digest(final String algorithm, final byte[] bytes) {
    try {
      final var prefix = algorithm.replace("-", "").toLowerCase(Locale.ROOT);

      return prefix
          + ":"
          + HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
