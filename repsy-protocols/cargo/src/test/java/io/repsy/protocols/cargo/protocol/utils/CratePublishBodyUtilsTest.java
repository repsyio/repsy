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
package io.repsy.protocols.cargo.protocol.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CratePublishBodyUtils")
class CratePublishBodyUtilsTest {

  private final tools.jackson.databind.ObjectMapper mapper =
      new tools.jackson.databind.ObjectMapper();

  private byte[] body(final byte[] lengthField, final String json) {
    final var out = new ByteArrayOutputStream();
    out.writeBytes(lengthField);
    out.writeBytes(json.getBytes(StandardCharsets.UTF_8));
    return out.toByteArray();
  }

  private byte[] u32(final long value) {
    return java.nio.ByteBuffer.allocate(4)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        .putInt((int) value)
        .array();
  }

  @Test
  @DisplayName("getPublishRequest reads a little-endian length and that many bytes of JSON")
  void readsPublishRequest() throws IOException {
    final var json = "{\"name\":\"demo\",\"vers\":\"1.0.0\",\"unknown\":1}";
    final var in = new ByteArrayInputStream(this.body(this.u32(json.length()), json + "TRAILING"));

    final var request = CratePublishBodyUtils.getPublishRequest(in, this.mapper);

    assertThat(request.name()).isEqualTo("demo");
    assertThat(request.vers()).isEqualTo("1.0.0");
    assertThat(in.readAllBytes()).isEqualTo("TRAILING".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("getPublishRequest refuses an empty, oversized, short or truncated body")
  void refusesBadPublishBodies() {
    assertThatThrownBy(
            () ->
                CratePublishBodyUtils.getPublishRequest(
                    new ByteArrayInputStream(this.u32(0)), this.mapper))
        .hasMessage("the crate's metadata JSON is empty");
    assertThatThrownBy(
            () ->
                CratePublishBodyUtils.getPublishRequest(
                    new ByteArrayInputStream(this.u32(5L * 1024 * 1024 + 1)), this.mapper))
        .hasMessage("the crate's metadata JSON must be at most 5 MiB");
    assertThatThrownBy(
            () ->
                CratePublishBodyUtils.getPublishRequest(
                    new ByteArrayInputStream(this.body(this.u32(50), "{}")), this.mapper))
        .hasMessage("the crate's metadata JSON is shorter than declared");
    assertThatThrownBy(
            () ->
                CratePublishBodyUtils.getPublishRequest(
                    new ByteArrayInputStream(new byte[] {1, 0}), this.mapper))
        .hasMessage("the publish body ends before a length field is complete");
  }

  @Test
  @DisplayName("readCrateLength reads an unsigned little-endian u32")
  void readsCrateLength() throws IOException {
    assertThat(
            CratePublishBodyUtils.readCrateLength(
                new ByteArrayInputStream(new byte[] {4, 3, 2, 1})))
        .isEqualTo(0x01020304L);
    assertThat(
            CratePublishBodyUtils.readCrateLength(
                new ByteArrayInputStream(
                    new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff})))
        .isEqualTo(4294967295L);
    assertThatThrownBy(
            () ->
                CratePublishBodyUtils.readCrateLength(
                    new ByteArrayInputStream(new byte[] {1, 2, 3})))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
