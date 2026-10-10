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

import java.util.OptionalLong;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;

/** The {@code Range} and {@code Content-Range} values of a blob upload session. */
@UtilityClass
@NullMarked
public final class OciUploadUtils {

  /**
   * The {@code Range} header of an upload that holds {@code size} bytes: {@code 0-<size - 1>}, and
   * {@code 0-0} for an empty upload (what the registry has always answered, though an empty upload
   * holds no byte 0).
   */
  public static String range(final long size) {
    return "0-" + Math.max(size - 1, 0);
  }

  /**
   * Parses the {@code start} of a {@code Content-Range: start-end} header, in the plain {@code
   * <start>-<end>} form this registry's own {@code Range}/{@code Location} responses use (not the
   * RFC 7233 {@code bytes .../...} form). Answers empty for anything else, so a header this cannot
   * parse falls back to appending the chunk rather than being treated as a mismatch.
   */
  public static OptionalLong parseContentRangeStart(final String contentRange) {

    final var dash = contentRange.indexOf('-');
    if (dash <= 0) {
      return OptionalLong.empty();
    }

    try {
      return OptionalLong.of(Long.parseLong(contentRange.substring(0, dash).trim()));
    } catch (final NumberFormatException _) {
      return OptionalLong.empty();
    }
  }
}
