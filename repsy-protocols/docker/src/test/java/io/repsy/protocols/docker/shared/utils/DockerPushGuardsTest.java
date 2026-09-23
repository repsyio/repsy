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
package io.repsy.protocols.docker.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("DockerPushGuards")
class DockerPushGuardsTest {

  private static String repeat(final char c, final int times) {
    return String.valueOf(c).repeat(times);
  }

  @Test
  @DisplayName("an image name of exactly the column length is accepted")
  void imageNameAtLimitIsAccepted() {
    assertThatCode(() -> DockerPushGuards.rejectInvalidImageName(repeat('a', 255)))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("an image name one character over the column length is refused")
  void imageNameOverLimitIsRejected() {
    assertThatThrownBy(() -> DockerPushGuards.rejectInvalidImageName(repeat('a', 256)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("dockerImageNameInvalid");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "has/slash", "has space", "has:colon", "has@at"})
  @DisplayName("an image name outside the grammar this registry stores names under is refused")
  void malformedImageNameIsRejected(final String name) {
    assertThatThrownBy(() -> DockerPushGuards.rejectInvalidImageName(name))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("dockerImageNameInvalid");
  }

  @ParameterizedTest
  @ValueSource(strings = {"latest", "1.0.0", "v1", "a", "a.b-c_d"})
  @DisplayName("a well formed tag is accepted")
  void wellFormedTagIsAccepted(final String tag) {
    assertThatCode(() -> DockerPushGuards.rejectInvalidReference(tag)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a tag of exactly the OCI grammar's 128 characters is accepted")
  void tagAtGrammarLimitIsAccepted() {
    assertThatCode(() -> DockerPushGuards.rejectInvalidReference(repeat('a', 128)))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a tag one character over the OCI grammar's 128 characters is refused")
  void tagOverGrammarLimitIsRejected() {
    assertThatThrownBy(() -> DockerPushGuards.rejectInvalidReference(repeat('a', 129)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("dockerReferenceInvalid");
  }

  @ParameterizedTest
  @ValueSource(strings = {".starts-with-dot", "-starts-with-dash", "has space", "has/slash"})
  @DisplayName("a tag outside the OCI grammar is refused")
  void malformedTagIsRejected(final String tag) {
    assertThatThrownBy(() -> DockerPushGuards.rejectInvalidReference(tag))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("dockerReferenceInvalid");
  }

  @Test
  @DisplayName("a sha256 digest is accepted")
  void sha256DigestIsAccepted() {
    assertThatCode(() -> DockerPushGuards.rejectInvalidReference("sha256:" + repeat('0', 64)))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a sha512 digest is accepted, and fits the varchar(255) reference column")
  void sha512DigestIsAccepted() {
    final var sha512Reference = "sha512:" + repeat('0', 128);
    assertThat(sha512Reference.length()).isLessThanOrEqualTo(DockerConstants.MAX_REFERENCE_LENGTH);
    assertThatCode(() -> DockerPushGuards.rejectInvalidReference(sha512Reference))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "sha256:tooshort",
        "sha1:0000000000000000000000000000000000000000",
        "sha256:" // no hex at all
      })
  @DisplayName("a colon-bearing reference of an unsupported or malformed digest is refused")
  void malformedDigestIsRejected(final String reference) {
    assertThatThrownBy(() -> DockerPushGuards.rejectInvalidReference(reference))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("dockerDigestInvalid");
  }

  @Test
  @DisplayName("a digest longer than the reference column is refused as a digest, not a tag")
  void overLongDigestIsRejectedAsADigest() {
    assertThatThrownBy(() -> DockerPushGuards.rejectInvalidReference("sha256:" + repeat('0', 300)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("dockerDigestInvalid");
  }

  @Test
  @DisplayName("a null media type is left for the caller's own presence check")
  void nullMediaTypeIsAccepted() {
    assertThatCode(() -> DockerPushGuards.rejectMediaTypeTooLong(null)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a media type of exactly the column length is accepted")
  void mediaTypeAtLimitIsAccepted() {
    assertThatCode(() -> DockerPushGuards.rejectMediaTypeTooLong(repeat('a', 255)))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a media type one character over the column length is refused")
  void mediaTypeOverLimitIsRejected() {
    assertThatThrownBy(() -> DockerPushGuards.rejectMediaTypeTooLong(repeat('a', 256)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("dockerMediaTypeTooLong");
  }

  @Test
  @DisplayName("a platform string of exactly the column length is accepted")
  void platformAtLimitIsAccepted() {
    assertThatCode(() -> DockerPushGuards.rejectPlatformTooLong(repeat('a', 255)))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a platform string one character over the column length is refused")
  void platformOverLimitIsRejected() {
    assertThatThrownBy(() -> DockerPushGuards.rejectPlatformTooLong(repeat('a', 256)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("dockerPlatformTooLong");
  }
}
