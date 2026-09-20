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
package io.repsy.protocols.shared.utils;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;

/**
 * An upload that was copied to a temporary file while it was read, so a package archive of any size
 * is checked and stored without being held in memory.
 *
 * <p>The copy computes the size and the SHA-256 of the upload on the way, and {@link #openStream()}
 * reads the file again as often as needed: once to parse the archive for its metadata and once to
 * store it. {@link #close()} deletes the file, so use it in a try-with-resources block.
 */
@Slf4j
@NullMarked
public final class SpooledUpload implements Closeable {

  private static final int BUFFER_SIZE = 64 * 1024;

  private final Path file;
  private final long size;
  private final String sha256Hex;

  private SpooledUpload(final Path file, final long size, final String sha256Hex) {
    this.file = file;
    this.size = size;
    this.sha256Hex = sha256Hex;
  }

  /**
   * Copies the whole stream to a temporary file. The stream is not closed.
   *
   * @param source The upload to copy
   * @param maxBytes The largest upload to accept, in bytes; the copy stops at the first byte past
   *     it, so an oversized upload never fills the disk
   * @return The spooled upload, which the caller has to close
   * @throws EntryTooLargeException If the upload is larger than {@code maxBytes}
   * @throws IOException If the upload cannot be read or the file cannot be written
   */
  public static SpooledUpload spool(final InputStream source, final long maxBytes)
      throws IOException {

    final var digest = newSha256();
    final var file = Files.createTempFile("repsy-upload-", ".tmp");

    try {
      final var buffer = new byte[BUFFER_SIZE];
      long total = 0;

      try (final var out = Files.newOutputStream(file)) {
        int read = source.read(buffer);

        while (read != -1) {
          total += read;

          if (total > maxBytes) {
            throw new EntryTooLargeException(maxBytes);
          }

          digest.update(buffer, 0, read);
          out.write(buffer, 0, read);
          read = source.read(buffer);
        }
      }

      return new SpooledUpload(file, total, HexFormat.of().formatHex(digest.digest()));
    } catch (final IOException | RuntimeException e) {
      deleteQuietly(file);
      throw e;
    }
  }

  /** Copies the whole stream to a temporary file, whatever its size. */
  public static SpooledUpload spool(final InputStream source) throws IOException {
    return spool(source, Long.MAX_VALUE);
  }

  /** The number of bytes the upload had. */
  public long size() {
    return this.size;
  }

  /** The SHA-256 of the upload, as lower-case hex. */
  public String sha256Hex() {
    return this.sha256Hex;
  }

  /** Opens the spooled bytes from the start. The caller closes the stream. */
  public InputStream openStream() throws IOException {
    return new BufferedInputStream(Files.newInputStream(this.file), BUFFER_SIZE);
  }

  /** Deletes the temporary file. */
  @Override
  public void close() {
    deleteQuietly(this.file);
  }

  private static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("Every Java runtime has to support SHA-256", e);
    }
  }

  private static void deleteQuietly(final Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (final IOException e) {
      log.warn("Could not delete the temporary upload file {}", file, e);
    }
  }
}
