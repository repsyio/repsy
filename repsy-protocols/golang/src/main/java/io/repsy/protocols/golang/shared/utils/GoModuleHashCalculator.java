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
package io.repsy.protocols.golang.shared.utils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;

/**
 * Calculates Go module h1: hashes as defined by golang.org/x/mod/dirhash.
 *
 * <p>For go.mod: {@code h1:base64(sha256("go.mod:hexsha256(content)\n"))}
 *
 * <p>For zip: sort {@code "filename:hexsha256(filecontent)\n"} lines, then {@code
 * h1:base64(sha256(joined_lines))}
 */
@UtilityClass
@NullMarked
public class GoModuleHashCalculator {

  private static final String H1_PREFIX = "h1:";
  private static final String SHA_256 = "SHA-256";
  private static final int BUFFER_SIZE = 8192;

  /** Returns the lowercase hex-encoded SHA-256 digest of {@code content}. */
  @SneakyThrows
  public static String computeSha256Hex(final byte[] content) {
    return toHex(MessageDigest.getInstance(SHA_256).digest(content));
  }

  @SneakyThrows
  public static String hashMod(final byte[] content) {
    final var digest = MessageDigest.getInstance(SHA_256);
    final var line = "go.mod:" + toHex(digest.digest(content)) + "\n";
    final var outerDigest = MessageDigest.getInstance(SHA_256);
    return H1_PREFIX
        + Base64.getEncoder()
            .encodeToString(outerDigest.digest(line.getBytes(StandardCharsets.UTF_8)));
  }

  @SneakyThrows
  public static String hashZip(final byte[] zipContent) {
    final var lines = buildZipEntryLines(zipContent);
    Collections.sort(lines);
    final var combined = String.join("", lines);
    final var digest = MessageDigest.getInstance(SHA_256);
    return H1_PREFIX
        + Base64.getEncoder()
            .encodeToString(digest.digest(combined.getBytes(StandardCharsets.UTF_8)));
  }

  @SneakyThrows
  private static List<String> buildZipEntryLines(final byte[] zipContent) {
    final List<String> lines = new ArrayList<>();
    try (final var zis = new ZipInputStream(new ByteArrayInputStream(zipContent))) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        if (!entry.isDirectory()) {
          lines.add(buildEntryLine(entry.getName(), zis));
        }
        zis.closeEntry();
      }
    }
    return lines;
  }

  @SneakyThrows
  private static String buildEntryLine(final String name, final InputStream stream) {
    final var digest = MessageDigest.getInstance(SHA_256);
    final var buf = new byte[BUFFER_SIZE];
    int n;
    while ((n = stream.read(buf)) != -1) {
      digest.update(buf, 0, n);
    }
    return name + ":" + toHex(digest.digest()) + "\n";
  }

  private static String toHex(final byte[] bytes) {
    final var sb = new StringBuilder(bytes.length * 2);
    for (final byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }
}
