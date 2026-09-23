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
package io.repsy.protocols.pypi.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.protocols.pypi.shared.python_package.dtos.PackageUploadForm;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

@DisplayName("PackageStorageUtils")
class PackageStorageUtilsTest {

  @Nested
  @DisplayName("isFileBelongsRelease()")
  class IsFileBelongsReleaseTests {

    @Test
    @DisplayName("true when the filename's own version matches the given version")
    void trueWhenVersionsMatch() {
      assertThat(PackageStorageUtils.isFileBelongsRelease("pkg-1.2.3-py3-none-any.whl", "1.2.3"))
          .isTrue();
    }

    @Test
    @DisplayName("false when the filename's own version does not match the given version")
    void falseWhenVersionsDiffer() {
      assertThat(
              PackageStorageUtils.isFileBelongsRelease("pkg-1.2.3-py3-none-any.whl", "1.2.3.post9"))
          .isFalse();
    }

    @Test
    @DisplayName("false when no version can be extracted from the filename")
    void falseWhenFilenameHasNoVersion() {
      assertThat(PackageStorageUtils.isFileBelongsRelease("not-a-valid-name", "1.2.3")).isFalse();
    }
  }

  @Nested
  @DisplayName("extractVersionFromArchiveFilename()")
  class ExtractVersionFromArchiveFilenameTests {

    @Test
    @DisplayName("extracts the version out of a wheel filename")
    void extractsFromWheel() {
      assertThat(
              PackageStorageUtils.extractVersionFromArchiveFilename("pkg-1.2.3-py3-none-any.whl"))
          .isEqualTo("1.2.3");
    }

    @Test
    @DisplayName("extracts the version out of an sdist filename")
    void extractsFromSdist() {
      assertThat(PackageStorageUtils.extractVersionFromArchiveFilename("pkg-1.2.3.tar.gz"))
          .isEqualTo("1.2.3");
    }

    @Test
    @DisplayName("returns null when the filename carries no recognisable version")
    void returnsNullWhenNoVersion() {
      assertThat(PackageStorageUtils.extractVersionFromArchiveFilename("no-version-here")).isNull();
    }

    @Test
    @DisplayName("returns null for a 2 KB dotted filename without running the version grammar")
    void returnsNullForOverLongFilename() {
      final var filename = "pkg-1" + ".1".repeat(1000) + ".tar.gz";

      assertThat(PackageStorageUtils.extractVersionFromArchiveFilename(filename)).isNull();
      assertThat(PackageStorageUtils.isFileBelongsRelease(filename, "1.0")).isFalse();
    }
  }

  @Nested
  @DisplayName("checkArchiveFilename()")
  class CheckArchiveFilenameTests {

    @Test
    @DisplayName("throws archiveFileNameNull when the multipart file has no original filename")
    void throwsWhenFilenameNull() {
      // MockMultipartFile coerces a null filename to "", so a real mock is needed to exercise the
      // actual getOriginalFilename() == null branch.
      final var file = mock(MultipartFile.class);
      when(file.getOriginalFilename()).thenReturn(null);

      assertThatThrownBy(() -> PackageStorageUtils.checkArchiveFilename(file))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("archiveFileNameNull");
    }

    @Test
    @DisplayName(
        "throws archiveFileNameInvalid when the filename does not match the archive grammar")
    void throwsWhenFilenameInvalid() {
      final var file = new MockMultipartFile("content", "not-a-real-archive", null, new byte[] {1});

      assertThatThrownBy(() -> PackageStorageUtils.checkArchiveFilename(file))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("archiveFileNameInvalid");
    }

    @Test
    @DisplayName("answers 400 pypiArchiveFileNameTooLong for a 2 KB dotted filename, not a 500")
    void rejectsOverLongDottedFilename() {
      final var filename = "pkg-1" + ".1".repeat(1000) + "-py3-none-any.whl";
      final var file = new MockMultipartFile("content", filename, null, new byte[] {1});

      assertThatThrownBy(() -> PackageStorageUtils.checkArchiveFilename(file))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("pypiArchiveFileNameTooLong");
    }

    @Test
    @DisplayName("a filename at the limit still goes through the archive grammar without overflow")
    void grammarHandlesTheLongestAllowedFilename() {
      final var suffix = ".whl";
      final var dotted = "1" + ".1".repeat((255 - "pkg-".length() - suffix.length() - 1) / 2);
      final var filename = "pkg-" + dotted + suffix;
      final var file = new MockMultipartFile("content", filename, null, new byte[] {1});

      assertThat(filename.length()).isLessThanOrEqualTo(255);
      assertThatCode(() -> PackageStorageUtils.checkArchiveFilename(file))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("passes for a well-formed wheel filename")
    void passesForWellFormedFilename() {
      final var file =
          new MockMultipartFile("content", "pkg-1.2.3-py3-none-any.whl", null, new byte[] {1});

      assertThatCode(() -> PackageStorageUtils.checkArchiveFilename(file))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("checkSha256Digest() (RPS-1224)")
  class CheckSha256DigestTests {

    @Test
    @DisplayName("throws sha256DigestMissing when the field is null")
    void throwsWhenNull() {
      final var form = new PackageUploadForm();
      form.setSha256_digest(null);

      assertThatThrownBy(() -> PackageStorageUtils.checkSha256Digest(form))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("sha256DigestMissing");
    }

    @Test
    @DisplayName("throws sha256DigestMissing when the field is blank")
    void throwsWhenBlank() {
      final var form = new PackageUploadForm();
      form.setSha256_digest("   ");

      assertThatThrownBy(() -> PackageStorageUtils.checkSha256Digest(form))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("sha256DigestMissing");
    }

    @Test
    @DisplayName("passes for a present, non-blank digest")
    void passesWhenPresent() {
      final var form = new PackageUploadForm();
      form.setSha256_digest("deadbeef");

      assertThatCode(() -> PackageStorageUtils.checkSha256Digest(form)).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("computeSha256() (RPS-1225)")
  class ComputeSha256Tests {

    @Test
    @DisplayName("matches a known SHA-256 vector, lowercase hex, no prefix")
    void matchesKnownVector() throws Exception {
      final var file =
          new MockMultipartFile(
              "content", "pkg-1.0.0.whl", null, "hello".getBytes(StandardCharsets.UTF_8));

      final var digest = PackageStorageUtils.computeSha256(file);

      assertThat(digest)
          .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")
          .hasSize(64)
          .isLowerCase();
    }

    @Test
    @DisplayName("reads the whole stream for a payload spanning multiple 8KB chunks")
    void readsWholeStreamForLargePayload() throws Exception {
      final var bytes = new byte[8192 * 3 + 17];
      for (var i = 0; i < bytes.length; i++) {
        bytes[i] = (byte) (i % 251);
      }
      final var file = new MockMultipartFile("content", "pkg-1.0.0.whl", null, bytes);

      final var expected =
          java.util.HexFormat.of()
              .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));

      assertThat(PackageStorageUtils.computeSha256(file)).isEqualTo(expected);
    }

    @Test
    @DisplayName("is stable across repeated calls (MultipartFile#getInputStream is re-readable)")
    void isStableAcrossRepeatedCalls() throws Exception {
      final var file =
          new MockMultipartFile(
              "content", "pkg-1.0.0.whl", null, "repeatable".getBytes(StandardCharsets.UTF_8));

      final var first = PackageStorageUtils.computeSha256(file);
      final var second = PackageStorageUtils.computeSha256(file);

      assertThat(first).isEqualTo(second);
    }
  }
}
