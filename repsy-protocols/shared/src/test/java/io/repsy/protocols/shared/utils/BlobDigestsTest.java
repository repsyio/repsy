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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BlobDigests")
class BlobDigestsTest {

  private static final byte[] CONTENT = "hello".getBytes(StandardCharsets.UTF_8);

  private static final String SHA256 =
      "sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

  private static final String SHA512 =
      "sha512:9b71d224bd62f3785d96d46ad3ea3d73319bfbc2890caadae2dff72519673ca7"
          + "2323c3d99ba5c11d7c7acc6e14b8c5da0c4663475c2e5c3adef46f73bcdec043";

  private static InputStream stream(final byte[] bytes) {
    return new ByteArrayInputStream(bytes);
  }

  @Test
  @DisplayName("matches() accepts content that hashes to the sha256 digest")
  void matchesSha256() throws IOException {
    assertThat(BlobDigests.matches(SHA256, stream(CONTENT))).isTrue();
  }

  @Test
  @DisplayName("matches() accepts content that hashes to the sha512 digest")
  void matchesSha512() throws IOException {
    assertThat(BlobDigests.matches(SHA512, stream(CONTENT))).isTrue();
  }

  @Test
  @DisplayName("matches() compares the hex without regard to case")
  void matchesUpperCaseHex() throws IOException {
    final var upper = "sha256:" + SHA256.substring("sha256:".length()).toUpperCase();

    assertThat(BlobDigests.matches(upper, stream(CONTENT))).isTrue();
  }

  @Test
  @DisplayName("matches() rejects content that hashes to another digest")
  void rejectsOtherContent() throws IOException {
    assertThat(BlobDigests.matches(SHA256, stream("hellp".getBytes(StandardCharsets.UTF_8))))
        .isFalse();
  }

  @Test
  @DisplayName("matches() hashes content larger than its read buffer")
  void hashesLargeContent() throws IOException {
    final var large = new byte[200_000];

    assertThat(
            BlobDigests.matches(
                "sha256:4cbbd9be0cba685835755f827758705db5a413c5494c34262cd25946a73e7582",
                stream(large)))
        .isTrue();
    assertThat(BlobDigests.matches("sha256:" + "0".repeat(64), stream(large))).isFalse();
  }

  @Test
  @DisplayName("matches() rejects an unsupported algorithm and malformed digests")
  void rejectsUnsupportedDigests() throws IOException {
    assertThat(BlobDigests.matches("md5:5d41402abc4b2a76b9719d911017c592", stream(CONTENT)))
        .isFalse();
    assertThat(BlobDigests.matches("sha256:abc", stream(CONTENT))).isFalse();
    assertThat(BlobDigests.matches("sha256", stream(CONTENT))).isFalse();
    assertThat(BlobDigests.matches("", stream(CONTENT))).isFalse();
    assertThat(BlobDigests.matches("sha256:" + "z".repeat(64), stream(CONTENT))).isFalse();
  }

  @Test
  @DisplayName("isSupportedAlgorithm() accepts exactly the OCI names of sha256 and sha512")
  void isSupportedAlgorithmChecksTheName() {
    assertThat(BlobDigests.isSupportedAlgorithm("sha256")).isTrue();
    assertThat(BlobDigests.isSupportedAlgorithm("sha512")).isTrue();
    assertThat(BlobDigests.isSupportedAlgorithm("sha384")).isFalse();
    assertThat(BlobDigests.isSupportedAlgorithm("SHA256")).isFalse();
    assertThat(BlobDigests.isSupportedAlgorithm("sha256:")).isFalse();
    assertThat(BlobDigests.isSupportedAlgorithm("")).isFalse();
  }

  @Test
  @DisplayName("isSupported() accepts sha256 and sha512 digests of the right length")
  void isSupportedChecksAlgorithmAndLength() {
    assertThat(BlobDigests.isSupported(SHA256)).isTrue();
    assertThat(BlobDigests.isSupported(SHA512)).isTrue();
    assertThat(BlobDigests.isSupported("sha512:" + "a".repeat(64))).isFalse();
    assertThat(BlobDigests.isSupported("sha384:" + "a".repeat(96))).isFalse();
    assertThat(BlobDigests.isSupported("sha256:")).isFalse();
  }

  @Test
  @DisplayName("matches() closes the stream it reads")
  void closesTheStream() throws IOException {
    final var closed = new boolean[1];
    final var content =
        new ByteArrayInputStream(CONTENT) {
          @Override
          public void close() throws IOException {
            closed[0] = true;
            super.close();
          }
        };

    BlobDigests.matches(SHA256, content);

    assertThat(closed[0]).isTrue();
  }

  @Test
  @DisplayName("matches() passes a read failure on")
  void passesAReadFailureOn() {
    final InputStream broken =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("disk gone");
          }
        };

    assertThatThrownBy(() -> BlobDigests.matches(SHA256, broken))
        .isInstanceOf(IOException.class)
        .hasMessage("disk gone");
  }

  @Test
  @DisplayName("DIGEST_REGEX matches exactly the digests isSupported() accepts")
  void digestRegexAgreesWithIsSupported() {
    final var pattern = java.util.regex.Pattern.compile("^" + BlobDigests.DIGEST_REGEX + "$");

    for (final var digest :
        java.util.List.of(
            SHA256,
            SHA512,
            "sha512:" + "a".repeat(64),
            "sha256:" + "a".repeat(128),
            "sha384:" + "a".repeat(96),
            "sha256:",
            "sha256:" + "z".repeat(64),
            "latest",
            "")) {
      assertThat(pattern.matcher(digest).matches())
          .as(digest)
          .isEqualTo(BlobDigests.isSupported(digest));
    }
  }

  @Test
  @DisplayName("digest prefix helpers find a sha256: or sha512: prefix and nothing else")
  void digestPrefixHelpers() {
    assertThat(BlobDigests.containsDigestPrefix("a/manifests/" + SHA256)).isTrue();
    assertThat(BlobDigests.containsDigestPrefix("a/manifests/" + SHA512)).isTrue();
    assertThat(BlobDigests.containsDigestPrefix("a/manifests/latest")).isFalse();
    assertThat(BlobDigests.containsDigestPrefix("a/manifests/sha384:abc")).isFalse();

    assertThat(BlobDigests.indexOfDigestPrefix("blobs/" + SHA512)).isEqualTo(6);
    assertThat(BlobDigests.indexOfDigestPrefix("blobs/sha512:x/sha256:y")).isEqualTo(6);
    assertThat(BlobDigests.indexOfDigestPrefix("blobs/sha256:x/sha512:y")).isEqualTo(6);
    assertThat(BlobDigests.indexOfDigestPrefix("blobs/uuid")).isEqualTo(-1);

    assertThat(BlobDigests.startsWithDigestPrefix(SHA256)).isTrue();
    assertThat(BlobDigests.startsWithDigestPrefix(SHA512)).isTrue();
    assertThat(BlobDigests.startsWithDigestPrefix("latest")).isFalse();
    assertThat(BlobDigests.startsWithDigestPrefix("tag-sha256:abc")).isFalse();
  }
}
