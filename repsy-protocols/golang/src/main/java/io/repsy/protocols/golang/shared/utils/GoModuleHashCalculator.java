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

import io.repsy.core.error_handling.exceptions.BadRequestException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;

/**
 * Calculates Go module {@code h1:} hashes exactly as {@code golang.org/x/mod/sumdb/dirhash}'s
 * {@code Hash1} does (RPS-1231): entries are sorted by name, each line is {@code
 * hex(sha256(content)) + " " + name + "\n"} (hex first, two spaces, then the name), and the outer
 * hash is {@code "h1:" + base64(sha256(joined lines))}. {@link #hashMod} always uses the single
 * literal name {@code "go.mod"} for its line, matching {@code dirhash.HashGoMod}, whatever the zip
 * entry it was read from was actually called.
 *
 * <p>The previous format, {@code name + ":" + hex + "\n"} with a colon instead of two spaces and
 * the name first, was not compatible with {@code go.sum}/{@code go mod download -json}'s {@code
 * Sum}/{@code GoModSum} fields for the same bytes. A hash stored by that format before this fix is
 * left as it is: nothing in Repsy itself reads these hashes back, only a Go client comparing them
 * against its own {@code go.sum}, so only new uploads get a hash it would recognise.
 */
@UtilityClass
@NullMarked
public class GoModuleHashCalculator {

  private static final String H1_PREFIX = "h1:";
  private static final String SHA_256 = "SHA-256";
  private static final int BUFFER_SIZE = 8192;
  private static final String GO_MOD_ENTRY_NAME = "go.mod";

  /**
   * The largest total of inflated (decompressed) bytes a module zip's entries may sum to while
   * being hashed. Deflate can compress at up to roughly 1000:1, so a small upload could otherwise
   * make this inflate hundreds of GiB, pinning a request thread and its CPU for a long time
   * (RPS-1118). 500 MiB matches {@code MaxZipFile} in {@code golang.org/x/mod/zip}, the limit the
   * {@code go} tool itself puts on a module zip, so no zip the toolchain would build or accept is
   * ever refused by it.
   */
  public static final long MAX_INFLATED_BYTES = 500L * 1024 * 1024;

  /**
   * The largest number of non-directory entries a module zip may hash. No real module comes
   * anywhere close to this; it only bounds the CPU a crafted zip with an enormous entry count can
   * burn while being walked, a failure mode {@link #MAX_INFLATED_BYTES} alone does not stop for a
   * zip of many small or empty entries (RPS-1118).
   */
  public static final int MAX_ENTRY_COUNT = 100_000;

  /** Returns the lowercase hex-encoded SHA-256 digest of {@code content}. */
  @SneakyThrows
  public static String computeSha256Hex(final byte[] content) {
    return HexFormat.of().formatHex(MessageDigest.getInstance(SHA_256).digest(content));
  }

  @SneakyThrows
  public static String hashMod(final byte[] content) {
    return outerHash(List.of(entryLine(computeSha256Hex(content), GO_MOD_ENTRY_NAME)));
  }

  /**
   * Hashes every non-directory entry of a module zip, sorted by name, into a {@code dirhash.Hash1}
   * value. Refuses a zip whose entries inflate past {@link #MAX_INFLATED_BYTES} in total, that has
   * more than {@link #MAX_ENTRY_COUNT} entries, or that has an entry name containing a newline (a
   * smuggling vector into the line format this method itself writes).
   *
   * @throws BadRequestException {@code moduleZipTooLarge}, {@code moduleZipTooManyFiles} or {@code
   *     moduleZipEntryNameInvalid}
   */
  public static String hashZip(final InputStream zipContent) {
    return hashZip(zipContent, MAX_INFLATED_BYTES, MAX_ENTRY_COUNT);
  }

  /** Package-visible so the unit test can exercise the limits without a multi-hundred-MiB zip. */
  @SneakyThrows
  static String hashZip(
      final InputStream zipContent, final long maxInflatedBytes, final int maxEntryCount) {

    final var hashes = new ArrayList<NamedHash>();
    final var totalInflated = new AtomicLong();

    try (final var zis = new ZipInputStream(zipContent)) {
      ZipEntry entry = zis.getNextEntry();

      while (entry != null) {
        if (!entry.isDirectory()) {
          if (hashes.size() >= maxEntryCount) {
            throw new BadRequestException("moduleZipTooManyFiles");
          }

          final var name = entry.getName();
          if (name.indexOf('\n') >= 0) {
            throw new BadRequestException("moduleZipEntryNameInvalid");
          }

          hashes.add(new NamedHash(name, hashEntry(zis, totalInflated, maxInflatedBytes)));
        }

        zis.closeEntry();
        entry = zis.getNextEntry();
      }
    }

    hashes.sort(Comparator.comparing(NamedHash::name));
    return outerHash(hashes.stream().map(h -> entryLine(h.hex(), h.name())).toList());
  }

  /**
   * Digests the current zip entry, adding the bytes it inflates to {@code totalInflated} as they
   * are read.
   */
  @SneakyThrows
  private static String hashEntry(
      final InputStream entryStream, final AtomicLong totalInflated, final long maxInflatedBytes) {

    final var digest = MessageDigest.getInstance(SHA_256);
    final var buf = new byte[BUFFER_SIZE];
    int n = entryStream.read(buf);

    while (n != -1) {
      if (totalInflated.addAndGet(n) > maxInflatedBytes) {
        throw new BadRequestException("moduleZipTooLarge");
      }
      digest.update(buf, 0, n);
      n = entryStream.read(buf);
    }

    return HexFormat.of().formatHex(digest.digest());
  }

  private record NamedHash(String name, String hex) {}

  private static String entryLine(final String hex, final String name) {
    return hex + "  " + name + "\n";
  }

  @SneakyThrows
  private static String outerHash(final List<String> lines) {
    final var combined = String.join("", lines);
    final var digest = MessageDigest.getInstance(SHA_256);
    return H1_PREFIX
        + Base64.getEncoder()
            .encodeToString(digest.digest(combined.getBytes(StandardCharsets.UTF_8)));
  }
}
