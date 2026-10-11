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
package io.repsy.protocols.oci.utils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("OciPushGuardUtils")
class OciPushGuardUtilsTest {

  private static final int MAX = 255;

  @ParameterizedTest
  @ValueSource(strings = {"1.0.0", "latest", "_x", "v1.2.3-rc.1"})
  @DisplayName("accepts a well-formed tag")
  void acceptsValidReferences(final String reference) {
    assertThatCode(() -> OciPushGuardUtils.rejectInvalidReference(reference, MAX))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {".hidden", "-dash", "has space", "1.0.0+build", ""})
  @DisplayName("refuses a tag the distribution grammar does not allow")
  void refusesInvalidTags(final String reference) {
    assertThatThrownBy(() -> OciPushGuardUtils.rejectInvalidReference(reference, MAX))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("dockerReferenceInvalid");
  }

  @Test
  @DisplayName("accepts a supported digest")
  void acceptsSupportedDigest() {
    assertThatCode(() -> OciPushGuardUtils.rejectInvalidReference("sha256:" + "a".repeat(64), MAX))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("refuses an unsupported digest as an invalid digest")
  void refusesUnsupportedDigest() {
    assertThatThrownBy(() -> OciPushGuardUtils.rejectInvalidReference("md5:abc", MAX))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("dockerDigestInvalid");
  }

  @Test
  @DisplayName("refuses a reference longer than the column")
  void refusesOverLongReference() {
    assertThatThrownBy(() -> OciPushGuardUtils.rejectInvalidReference("a".repeat(129), 128))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("dockerReferenceInvalid");
  }
}
