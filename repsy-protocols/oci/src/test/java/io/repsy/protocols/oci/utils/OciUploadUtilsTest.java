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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("OCI upload Range and Content-Range values")
class OciUploadUtilsTest {

  @Test
  @DisplayName("the Range of an upload is 0 to its last byte, and 0-0 when it is empty")
  void rangeCoversTheWrittenBytes() {
    assertThat(OciUploadUtils.range(12)).isEqualTo("0-11");
    assertThat(OciUploadUtils.range(1)).isEqualTo("0-0");
    assertThat(OciUploadUtils.range(0)).isEqualTo("0-0");
  }

  @Test
  @DisplayName("the plain start-end form is parsed to its start, around spaces")
  void plainContentRangeIsParsed() {
    assertThat(OciUploadUtils.parseContentRangeStart("6-10")).isEqualTo(OptionalLong.of(6));
    assertThat(OciUploadUtils.parseContentRangeStart(" 0 -3")).isEqualTo(OptionalLong.of(0));
  }

  @ParameterizedTest
  @ValueSource(strings = {"bytes 11-11/12", "bytes=0-3", "-5", "5", "", "x-y"})
  @DisplayName("any other form is not parsed, so the chunk is appended")
  void otherFormsAreNotParsed(final String contentRange) {
    assertThat(OciUploadUtils.parseContentRangeStart(contentRange)).isEmpty();
  }
}
