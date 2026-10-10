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
package io.repsy.protocols.cargo.protocol.utils;

import io.repsy.protocols.shared.utils.BoundedEntryReader;
import io.repsy.protocols.shared.utils.EntryTooLargeException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.jspecify.annotations.Nullable;

/** Inspects a spooled {@code .crate} tarball for its library target and edition. */
@Slf4j
@UtilityClass
public class CrateInspectionUtils {

  private static final long MEBIBYTE = 1024L * 1024L;

  /**
   * The largest {@code Cargo.toml} a crate may carry, in bytes. A real manifest is a few kilobytes,
   * and even one that lists thousands of features stays far below this; the limit only has to stop
   * a decompression bomb, which the tar header size lets {@link #inspectCrate(InputStream)} refuse
   * before it inflates any of it.
   */
  public static final long MAX_CARGO_TOML_BYTES = 10 * MEBIBYTE;

  /** {@code cargo_crate_meta.edition}: a longer value is dropped rather than refused (RPS-1141). */
  private static final int MAX_EDITION_LENGTH = 10;

  private static final Pattern EDITION_LINE = Pattern.compile("^edition\\s*=\\s*\"([^\"]*)\"");

  /** Whether the crate has a library target, and the edition its manifest declares, if any. */
  public record CrateInspection(boolean hasLib, @Nullable String edition) {}

  /**
   * Makes one pass over the spooled {@code .crate} tarball, reading whichever of the two entries it
   * needs to answer both {@link CrateInspection#hasLib()} (an {@code src/lib.rs}, or a {@code
   * [lib]} table in {@code Cargo.toml}) and {@link CrateInspection#edition()} (the {@code edition}
   * key of {@code Cargo.toml}'s {@code [package]} table, RPS-1141). Only the first {@code
   * Cargo.toml} whose {@code [package]} table declares an edition is used, which is the crate's own
   * manifest: it is written before any nested one a vendored path dependency might carry. An
   * edition over {@link #MAX_EDITION_LENGTH} characters (the column's width) is dropped rather than
   * refused, the same way the descriptive metadata in {@link
   * CratePublishRequestUtils#dropOverLongMetadata(CratePublishRequest)} is.
   */
  public static CrateInspection inspectCrate(final InputStream crateStream) throws IOException {
    try (final var tar = new TarArchiveInputStream(new GzipCompressorInputStream(crateStream))) {

      var hasLib = false;
      String edition = null;

      TarArchiveEntry entry;

      while ((entry = tar.getNextEntry()) != null) {
        final var entryName = entry.getName();

        if (entryName.endsWith("/src/lib.rs")) {
          hasLib = true;
        }

        if (entryName.endsWith("/Cargo.toml")) {
          final var toml = new String(readCargoToml(tar, entry), StandardCharsets.UTF_8);

          if (toml.lines().anyMatch(line -> line.trim().equals("[lib]"))) {
            hasLib = true;
          }

          if (edition == null) {
            edition = extractEdition(toml);
          }
        }
      }
      return new CrateInspection(hasLib, edition);
    }
  }

  /** Reads the {@code edition} key of the manifest's {@code [package]} table, if it has one. */
  private static @Nullable String extractEdition(final String toml) {

    var inPackageTable = false;

    for (final var rawLine : toml.lines().toList()) {
      final var line = rawLine.trim();

      if (isTableHeader(line)) {
        inPackageTable = "[package]".equals(line);
        continue;
      }

      if (!inPackageTable) {
        continue;
      }

      final var edition = matchEditionValue(line);

      if (edition != null) {
        return trimToColumnWidth(edition);
      }
    }

    return null;
  }

  private static boolean isTableHeader(final String line) {
    return line.startsWith("[") && line.endsWith("]");
  }

  private static @Nullable String matchEditionValue(final String line) {
    final var matcher = EDITION_LINE.matcher(line);
    return matcher.find() ? matcher.group(1) : null;
  }

  private static @Nullable String trimToColumnWidth(final String edition) {
    if (edition.length() > MAX_EDITION_LENGTH) {
      log.warn("Skipping edition: longer than {} characters", MAX_EDITION_LENGTH);
      return null;
    }

    return edition;
  }

  private static byte[] readCargoToml(final InputStream tar, final TarArchiveEntry entry)
      throws IOException {

    try {
      return BoundedEntryReader.readAllBytes(tar, entry.getSize(), MAX_CARGO_TOML_BYTES);
    } catch (final EntryTooLargeException e) {
      throw new IllegalArgumentException(
          "Cargo.toml in the crate must be at most %d MiB"
              .formatted(MAX_CARGO_TOML_BYTES / MEBIBYTE),
          e);
    }
  }
}
