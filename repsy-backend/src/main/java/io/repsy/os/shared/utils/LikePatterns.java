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
package io.repsy.os.shared.utils;

import java.util.Locale;
import org.jspecify.annotations.NonNull;

/** Builds {@code LIKE} patterns from text a caller typed. */
public final class LikePatterns {

  private LikePatterns() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * The lower-cased {@code LIKE} pattern of {@code query}, with its own wildcards ({@code %},
   * {@code _}) and the escape character ({@code \}) taken literally.
   *
   * <p>The query that uses it matches with {@code lower(column) like :pattern escape '\\'}.
   *
   * @param prefix Pattern text put before the query, such as {@code %}
   * @param query What the caller typed
   * @param suffix Pattern text put after the query, such as {@code %}
   */
  public static @NonNull String of(
      final @NonNull String prefix, final @NonNull String query, final @NonNull String suffix) {

    final var escaped =
        query
            .toLowerCase(Locale.ROOT)
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");

    return prefix + escaped + suffix;
  }
}
