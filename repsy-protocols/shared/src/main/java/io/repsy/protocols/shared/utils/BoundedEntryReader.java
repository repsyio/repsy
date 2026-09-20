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

import java.io.IOException;
import java.io.InputStream;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;

/**
 * Reads one entry of an uploaded archive (a {@code .crate}, {@code .nupkg}, module zip, ...) into
 * memory without trusting its inflated size. A few kilobytes of compressed data can inflate to
 * gigabytes, and {@link InputStream#readAllBytes()} would buffer all of it, so a publisher could
 * exhaust the heap of the whole instance with a small upload.
 */
@UtilityClass
@NullMarked
public class BoundedEntryReader {

  /** The longest array the JVM reliably allocates, which the one extra byte read must still fit. */
  private static final int MAX_ARRAY_LENGTH = Integer.MAX_VALUE - 8;

  /**
   * Reads the whole entry, refusing one that is larger than {@code maxBytes}.
   *
   * <p>The size the archive header declares is checked first, so an honest oversized entry is
   * refused before any of it is inflated. The read itself is bounded too, because a header (a zip
   * one in particular) can understate the size, or not carry it at all.
   *
   * @param entry The stream positioned at the entry's data; it is left unfinished on failure
   * @param declaredSize The size in the entry's header, or a negative number if it has none
   * @param maxBytes The largest entry to accept, in bytes; must be positive and fit an array
   * @return The entry's bytes, at most {@code maxBytes} of them
   * @throws EntryTooLargeException If the entry is larger than {@code maxBytes}
   * @throws IOException If the entry cannot be read
   */
  public static byte[] readAllBytes(
      final InputStream entry, final long declaredSize, final long maxBytes) throws IOException {

    if (maxBytes <= 0 || maxBytes >= MAX_ARRAY_LENGTH) {
      throw new IllegalArgumentException(
          "maxBytes must be between 1 and %d".formatted(MAX_ARRAY_LENGTH - 1));
    }

    if (declaredSize > maxBytes) {
      throw new EntryTooLargeException(maxBytes);
    }

    // One byte past the limit is enough to tell an entry that fits from one that does not, and
    // readNBytes grows its buffer as data arrives instead of allocating the limit up front.
    final var bytes = entry.readNBytes((int) maxBytes + 1);

    if (bytes.length > maxBytes) {
      throw new EntryTooLargeException(maxBytes);
    }

    return bytes;
  }
}
