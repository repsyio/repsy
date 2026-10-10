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
package io.repsy.protocols.shared.limits;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Drops a descriptive field that is longer than its column. A cut value would say something else (a
 * URL would point elsewhere), so an over-long value is dropped whole and the publish goes through.
 */
@Slf4j
@NullMarked
public final class FieldLimits {

  private FieldLimits() {
    throw new UnsupportedOperationException("Utility class");
  }

  /** {@code value}, or {@code null} when it is longer than {@code maxLength}. */
  public static @Nullable String dropIfTooLong(final @Nullable String value, final int maxLength) {

    return value != null && value.length() > maxLength ? null : value;
  }

  /** As {@link #dropIfTooLong(String, int)}, and logs the drop under the name of {@code field}. */
  public static @Nullable String dropIfTooLong(
      final @Nullable String value, final int maxLength, final String field) {

    if (value != null && value.length() > maxLength) {
      log.warn("Skipping {}: longer than {} characters", field, maxLength);
      return null;
    }

    return value;
  }

  /** The entries of {@code values} that fit {@code maxLength}, or {@code null} for {@code null}. */
  public static @Nullable List<String> dropEntriesIfTooLong(
      final @Nullable List<String> values, final int maxLength, final String field) {

    if (values == null) {
      return null;
    }

    return values.stream()
        .filter(
            value -> {
              final var fits = value == null || value.length() <= maxLength;
              if (!fits) {
                log.warn("Skipping a {}: longer than {} characters", field, maxLength);
              }
              return fits;
            })
        .toList();
  }
}
