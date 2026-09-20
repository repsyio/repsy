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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SpooledUpload")
class SpooledUploadTest {

  private static final byte[] CONTENT =
      "the quick brown fox".repeat(10_000).getBytes(StandardCharsets.UTF_8);

  private static String sha256Of(final byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static Set<Path> spooledFiles() throws IOException {
    try (final Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
      return files
          .filter(path -> path.getFileName().toString().startsWith("repsy-upload-"))
          .collect(Collectors.toSet());
    }
  }

  @Test
  @DisplayName("spool() keeps the bytes, and reports their size and SHA-256")
  void spoolsBytesSizeAndDigest() throws Exception {
    try (final var upload = SpooledUpload.spool(new ByteArrayInputStream(CONTENT))) {
      assertThat(upload.size()).isEqualTo(CONTENT.length);
      assertThat(upload.sha256Hex()).isEqualTo(sha256Of(CONTENT));

      try (final var in = upload.openStream()) {
        assertThat(in.readAllBytes()).isEqualTo(CONTENT);
      }
    }
  }

  @Test
  @DisplayName("openStream() reads the upload from the start every time")
  void canBeReadAgain() throws Exception {
    try (final var upload = SpooledUpload.spool(new ByteArrayInputStream(CONTENT))) {
      try (final var in = upload.openStream()) {
        assertThat(in.readNBytes(10)).hasSize(10);
      }

      try (final var in = upload.openStream()) {
        assertThat(in.readAllBytes()).isEqualTo(CONTENT);
      }
    }
  }

  @Test
  @DisplayName("spool() accepts an empty upload")
  void spoolsEmptyUpload() throws Exception {
    try (final var upload = SpooledUpload.spool(new ByteArrayInputStream(new byte[0]))) {
      assertThat(upload.size()).isZero();
      assertThat(upload.sha256Hex()).isEqualTo(sha256Of(new byte[0]));
    }
  }

  @Test
  @DisplayName("spool() accepts an upload of exactly the limit")
  void acceptsUploadAtLimit() throws Exception {
    try (final var upload =
        SpooledUpload.spool(new ByteArrayInputStream(CONTENT), CONTENT.length)) {
      assertThat(upload.size()).isEqualTo(CONTENT.length);
    }
  }

  @Test
  @DisplayName("spool() refuses an upload past the limit and leaves no file behind")
  void refusesUploadPastLimit() throws Exception {
    final var before = spooledFiles();

    assertThatThrownBy(
            () -> SpooledUpload.spool(new ByteArrayInputStream(CONTENT), CONTENT.length - 1L))
        .isInstanceOfSatisfying(
            EntryTooLargeException.class,
            e -> assertThat(e.getMaxBytes()).isEqualTo(CONTENT.length - 1L));

    assertThat(spooledFiles()).isEqualTo(before);
  }

  @Test
  @DisplayName("spool() leaves no file behind when the source fails halfway")
  void leavesNoFileWhenSourceFails() throws Exception {
    final var before = spooledFiles();
    final InputStream failing =
        new InputStream() {
          private int served;

          @Override
          public int read() throws IOException {
            throw new IOException("connection reset");
          }

          @Override
          public int read(final byte[] buffer, final int off, final int len) throws IOException {
            if (this.served > 0) {
              throw new IOException("connection reset");
            }
            this.served = Math.min(len, 100);
            return this.served;
          }
        };

    assertThatThrownBy(() -> SpooledUpload.spool(failing))
        .isInstanceOf(IOException.class)
        .hasMessage("connection reset");

    assertThat(spooledFiles()).isEqualTo(before);
  }

  @Test
  @DisplayName("spool() does not close the source")
  void doesNotCloseSource() throws Exception {
    final var closed = new boolean[1];
    final InputStream source =
        new ByteArrayInputStream(CONTENT) {
          @Override
          public void close() throws IOException {
            closed[0] = true;
            super.close();
          }
        };

    try (final var upload = SpooledUpload.spool(source)) {
      assertThat(upload.size()).isEqualTo(CONTENT.length);
    }

    assertThat(closed[0]).isFalse();
  }

  @Test
  @DisplayName("close() deletes the temporary file, and can be called twice")
  void closeDeletesFile() throws Exception {
    final var before = spooledFiles();
    final var upload = SpooledUpload.spool(new ByteArrayInputStream(CONTENT));

    assertThat(spooledFiles()).hasSize(before.size() + 1);

    upload.close();
    upload.close();

    assertThat(spooledFiles()).isEqualTo(before);
  }
}
