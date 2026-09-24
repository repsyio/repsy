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
package io.repsy.protocols.npm.shared.utils;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.jspecify.annotations.NullMarked;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads what an npm tarball vouches for, so metadata that was lost can be rebuilt from it: the
 * {@code package.json} the package was published with (the one place its dependencies and scripts
 * survive) and the digests that {@code dist} carries.
 */
@UtilityClass
@NullMarked
public final class NpmTarballInspector {

  /** {@code <top directory>/package.json}, whatever the top directory is called. */
  private static final Pattern MANIFEST_PATH = Pattern.compile("(?:\\./)?[^/]+/package\\.json");

  /** A manifest larger than this is not read: it is not a package.json anyone published. */
  private static final long MAX_MANIFEST_BYTES = 1L << 20;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Reads the tarball to its end: the digests cover every byte, and the manifest is taken on the
   * way when the tarball is a readable gzipped tar.
   *
   * @throws IOException when the stream cannot be read to its end
   */
  public static NpmTarballFacts inspect(final InputStream tarball) throws IOException {

    final var sha1 = DigestUtils.getSha1Digest();
    final var sha512 = DigestUtils.getSha512Digest();

    try (final var digested = new DigestInputStream(new DigestInputStream(tarball, sha1), sha512)) {
      final var manifest = readManifest(digested);

      // The tar reader stops at the end of the archive: what follows still belongs to the digests.
      digested.transferTo(OutputStream.nullOutputStream());

      return new NpmTarballFacts(
          manifest,
          Hex.encodeHexString(sha1.digest()),
          "sha512-" + Base64.encodeBase64String(sha512.digest()));
    }
  }

  /** The manifest, or an empty map when the tarball has none or is not a tar.gz. */
  private static Map<String, Object> readManifest(final InputStream tarball) {

    try (final var tar =
        new TarArchiveInputStream(new GzipCompressorInputStream(new Open(tarball)))) {

      return findManifest(tar);
    } catch (final IOException | RuntimeException _) {
      return Map.of();
    }
  }

  private static Map<String, Object> findManifest(final TarArchiveInputStream tar)
      throws IOException {

    var entry = tar.getNextEntry();

    while (entry != null) {
      if (entry.isFile()
          && MANIFEST_PATH.matcher(entry.getName()).matches()
          && entry.getSize() <= MAX_MANIFEST_BYTES) {
        return MAPPER.readValue(tar.readNBytes((int) entry.getSize()), new TypeReference<>() {});
      }

      entry = tar.getNextEntry();
    }

    return Map.of();
  }

  /**
   * Lets the archive readers close their stream without closing the one they read from, and keeps
   * them from rewinding it.
   */
  private static final class Open extends FilterInputStream {

    Open(final InputStream in) {
      super(in);
    }

    @Override
    public void close() {
      // The caller still has to read what the archive readers did not.
    }

    // Reading twice would digest twice: the archive readers get a stream that cannot be rewound.
    @Override
    public boolean markSupported() {
      return false;
    }

    @Override
    public synchronized void mark(final int readLimit) {
      // Not supported.
    }

    @Override
    public synchronized void reset() throws IOException {
      throw new IOException("mark/reset not supported");
    }
  }
}
