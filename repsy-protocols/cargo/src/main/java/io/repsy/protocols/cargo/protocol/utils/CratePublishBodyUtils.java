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

import io.repsy.protocols.cargo.shared.crate.dtos.CratePublishRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import lombok.experimental.UtilityClass;
import tools.jackson.databind.ObjectMapper;

/** Reads the parts of Cargo's publish wire format: the metadata JSON and the crate length. */
@UtilityClass
public class CratePublishBodyUtils {

  private static final long MEBIBYTE = 1024L * 1024L;

  /**
   * The largest publish-metadata JSON a request may carry, in bytes (RPS-1119). A real {@code cargo
   * publish} metadata document is a few kilobytes even for a crate with a long dependency list;
   * this only has to stop a client-declared length that would otherwise be cast straight into an
   * {@code int} and read into memory unbounded.
   */
  public static final long MAX_METADATA_JSON_BYTES = 5 * MEBIBYTE;

  private static long readU32LittleEndian(final InputStream inputStream) throws IOException {

    final var bytes = inputStream.readNBytes(Integer.BYTES);

    // A body that stops inside a length field is the client's mistake, not a server fault: the
    // buffer underflow it would otherwise end in surfaced as a 500 (RPS-1466).
    if (bytes.length < Integer.BYTES) {
      throw new IllegalArgumentException("the publish body ends before a length field is complete");
    }

    return Integer.toUnsignedLong(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt());
  }

  /**
   * Reads the publish-metadata JSON that precedes the {@code .crate} in Cargo's wire format: a
   * little-endian {@code u32} length, then that many bytes of JSON. The length is a value the
   * client sent and is capped at {@link #MAX_METADATA_JSON_BYTES} before it is cast to an {@code
   * int} and read, so a malicious or corrupt length can neither wrap negative nor force an
   * unbounded read into memory (RPS-1119). A body that ends before the declared length is refused
   * rather than silently parsed from a short buffer.
   */
  public static CratePublishRequest getPublishRequest(
      final InputStream inputStream, final ObjectMapper objectMapper) throws IOException {

    final var jsonLength = readU32LittleEndian(inputStream);

    if (jsonLength == 0) {
      throw new IllegalArgumentException("the crate's metadata JSON is empty");
    }

    if (jsonLength > MAX_METADATA_JSON_BYTES) {
      throw new IllegalArgumentException(
          "the crate's metadata JSON must be at most %d MiB"
              .formatted(MAX_METADATA_JSON_BYTES / MEBIBYTE));
    }

    final var jsonBytes = inputStream.readNBytes((int) jsonLength);

    if (jsonBytes.length != jsonLength) {
      throw new IllegalArgumentException("the crate's metadata JSON is shorter than declared");
    }

    return objectMapper.readValue(jsonBytes, CratePublishRequest.class);
  }

  /**
   * Reads the length prefix (a little-endian {@code u32}) that precedes the {@code .crate} bytes in
   * Cargo's wire format. The length is untrusted client input; the caller checks it against the
   * configured maximum crate size and then spools exactly this many bytes, instead of casting it
   * straight to an {@code int} and reading it all into memory (RPS-1119).
   */
  public static long readCrateLength(final InputStream inputStream) throws IOException {

    return readU32LittleEndian(inputStream);
  }
}
