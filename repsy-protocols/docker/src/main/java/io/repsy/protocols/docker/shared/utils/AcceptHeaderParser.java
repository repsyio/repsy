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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.util.StringUtils;

@UtilityClass
@NullMarked
public final class AcceptHeaderParser {

  public static List<String> parse(
      final @Nullable String acceptHeader, final List<String> defaultTypes) {

    Objects.requireNonNull(defaultTypes, "Default types cannot be null");

    if (!StringUtils.hasText(acceptHeader)) {
      return List.copyOf(defaultTypes);
    }

    return Arrays.stream(acceptHeader.split(","))
        .map(String::trim)
        .filter(StringUtils::hasText)
        .map(AcceptHeaderParser::removeQualityValue)
        .filter(StringUtils::hasText)
        .distinct()
        .toList();
  }

  public static List<String> parse(final @Nullable String acceptHeader, final String defaultType) {

    Objects.requireNonNull(defaultType, "Default type cannot be null");

    return parse(acceptHeader, List.of(defaultType));
  }

  private static String removeQualityValue(final String mediaType) {

    final var semicolonIndex = mediaType.indexOf(';');

    return semicolonIndex > 0 ? mediaType.substring(0, semicolonIndex).trim() : mediaType;
  }

  public static boolean contains(
      final @Nullable String acceptHeader, final String targetMediaType) {

    if (!StringUtils.hasText(acceptHeader) || !StringUtils.hasText(targetMediaType)) {
      return false;
    }

    return parse(acceptHeader, List.of()).contains(targetMediaType);
  }

  public static String getFirst(final @Nullable String acceptHeader, final String defaultType) {

    final var parsed = parse(acceptHeader, defaultType);

    return parsed.isEmpty() ? defaultType : parsed.getFirst();
  }
}
