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
package io.repsy.os.server.protocols.docker.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("PathParserUtils")
class PathParserUtilsTest {

  private static final String SHA256 = "sha256:" + "ab".repeat(32);
  private static final String SHA512 = "sha512:" + "cd".repeat(64);
  private static final String FILE_NAME = "some-storage-name";

  @ParameterizedTest
  @ValueSource(strings = {SHA256, SHA512})
  @DisplayName("a manifest digest reference is parsed to the digest itself, for either algorithm")
  void manifestByDigest(final String digest) {
    final var parsed =
        PathParserUtils.parseForManifest("/v2/repo/image/manifests/" + digest, digest);

    assertThat(parsed.getRepoName()).isEqualTo("repo");
    assertThat(parsed.getImageName()).isEqualTo("image");
    assertThat(parsed.getRelativePath().getPath()).isEqualTo("/manifests/" + digest);
  }

  @Test
  @DisplayName("a manifest tag reference is parsed to the storage file name, as before")
  void manifestByTag() {
    final var parsed =
        PathParserUtils.parseForManifest("/v2/repo/image/manifests/latest", FILE_NAME);

    assertThat(parsed.getImageName()).isEqualTo("image");
    assertThat(parsed.getRelativePath().getPath()).isEqualTo("/manifests/" + FILE_NAME);
  }

  @ParameterizedTest
  @ValueSource(strings = {SHA256, SHA512})
  @DisplayName("a manifest digest reference is parsed the same with a query string")
  void manifestByDigestIgnoresQuery(final String digest) {
    final var parsed =
        PathParserUtils.parseForManifest("/v2/repo/image/manifests/" + digest + "?x=1", FILE_NAME);

    assertThat(parsed.getRelativePath().getPath()).isEqualTo("/manifests/" + FILE_NAME);
  }

  @ParameterizedTest
  @ValueSource(strings = {SHA256, SHA512})
  @DisplayName("a blob digest is parsed to the digest itself, for either algorithm")
  void layerByDigest(final String digest) {
    final var parsed = PathParserUtils.parseForLayer("/v2/repo/image/blobs/" + digest, digest);

    assertThat(parsed.getRepoName()).isEqualTo("repo");
    assertThat(parsed.getImageName()).isEqualTo("image");
    assertThat(parsed.getRelativePath().getPath()).isEqualTo("/blobs/" + digest);
  }

  @Test
  @DisplayName("an upload session path is parsed to the file name, as before")
  void layerUpload() {
    final var parsed =
        PathParserUtils.parseForLayer("/v2/repo/image/blobs/uploads/0-1-2", FILE_NAME);

    assertThat(parsed.getRelativePath().getPath()).isEqualTo("/blobs/" + FILE_NAME);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "sha256:" + "ab",
        "sha512:" + "cd",
        "sha512:" + "cd".repeat(32),
        "sha256:" + "ab".repeat(64),
        "sha256:" + "zz".repeat(32),
        "sha512:" + "zz".repeat(64)
      })
  @DisplayName("a malformed digest reference is a bad request, not a tag")
  void malformedDigestIsRefused(final String digest) {
    assertThatThrownBy(
            () -> PathParserUtils.parseForManifest("/v2/repo/image/manifests/" + digest, FILE_NAME))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("manifest sha");
    assertThatThrownBy(
            () -> PathParserUtils.parseForLayer("/v2/repo/image/blobs/" + digest, FILE_NAME))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("layer check");
  }

  @Test
  @DisplayName("an unsupported digest algorithm is still refused as a tag path")
  void unsupportedAlgorithmIsRefused() {
    assertThatThrownBy(
            () ->
                PathParserUtils.parseForManifest(
                    "/v2/repo/image/manifests/sha384:" + "ab".repeat(48), FILE_NAME))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("manifest tag");
  }
}
