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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BoundedEntryReader")
class BoundedEntryReaderTest {

  private static final long LIMIT = 16;

  /** A stream that never ends, counting what was pulled out of it. */
  private static final class EndlessStream extends InputStream {

    private final AtomicLong served = new AtomicLong();

    @Override
    public int read() {
      this.served.incrementAndGet();
      return 'x';
    }

    @Override
    public int read(final byte[] buffer, final int offset, final int length) {
      Arrays.fill(buffer, offset, offset + length, (byte) 'x');
      this.served.addAndGet(length);
      return length;
    }
  }

  private static InputStream stream(final int size) {
    return new ByteArrayInputStream("x".repeat(size).getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("returns an entry below the limit")
  void readsSmallEntry() throws IOException {
    assertThat(BoundedEntryReader.readAllBytes(stream(5), 5, LIMIT)).hasSize(5);
  }

  @Test
  @DisplayName("returns an empty entry")
  void readsEmptyEntry() throws IOException {
    assertThat(BoundedEntryReader.readAllBytes(stream(0), 0, LIMIT)).isEmpty();
  }

  @Test
  @DisplayName("returns an entry of exactly the limit")
  void readsEntryAtLimit() throws IOException {
    assertThat(BoundedEntryReader.readAllBytes(stream((int) LIMIT), LIMIT, LIMIT))
        .hasSize((int) LIMIT);
  }

  @Test
  @DisplayName("refuses an entry whose header declares more than the limit, before reading it")
  void refusesOversizedHeader() {
    final var endless = new EndlessStream();

    assertThatThrownBy(() -> BoundedEntryReader.readAllBytes(endless, LIMIT + 1, LIMIT))
        .isInstanceOf(EntryTooLargeException.class)
        .hasMessageContaining(String.valueOf(LIMIT))
        .extracting(e -> ((EntryTooLargeException) e).getMaxBytes())
        .isEqualTo(LIMIT);
    assertThat(endless.served).hasValue(0);
  }

  @Test
  @DisplayName("refuses an entry one byte over the limit when the header says nothing")
  void refusesOneByteOverWithoutHeaderSize() {
    assertThatThrownBy(() -> BoundedEntryReader.readAllBytes(stream((int) LIMIT + 1), -1, LIMIT))
        .isInstanceOf(EntryTooLargeException.class);
  }

  @Test
  @DisplayName("stops reading an endless entry whose header understates its size")
  void stopsReadingAnEndlessEntry() {
    final var endless = new EndlessStream();

    assertThatThrownBy(() -> BoundedEntryReader.readAllBytes(endless, 1, LIMIT))
        .isInstanceOf(EntryTooLargeException.class);
    // A bomb would be read forever; the reader must pull at most a buffer's worth past the limit.
    assertThat(endless.served.get()).isLessThan(64L * 1024);
  }

  @Test
  @DisplayName("rejects a limit that is not positive or does not fit an array")
  void rejectsInvalidLimit() {
    assertThatThrownBy(() -> BoundedEntryReader.readAllBytes(stream(1), 1, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BoundedEntryReader.readAllBytes(stream(1), 1, Integer.MAX_VALUE))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
