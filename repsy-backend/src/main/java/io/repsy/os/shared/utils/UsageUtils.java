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

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.jspecify.annotations.NonNull;

public final class UsageUtils {
  private static final long KILOBYTE = 1024L;
  private static final long MEGABYTE = 1048576L;
  private static final long GIGABYTE = 1073741824L;
  private static final long TERABYTE = 1099511627776L;
  private static final long[] UNIT_SIZES = {KILOBYTE, MEGABYTE, GIGABYTE, TERABYTE};
  private static final String[] UNIT_NAMES = {"KB", "MB", "GB", "TB"};
  private static final BigDecimal NEXT_UNIT_THRESHOLD = BigDecimal.valueOf(KILOBYTE);

  private UsageUtils() {
    throw new UnsupportedOperationException("Utility class");
  }

  public static @NonNull String humanReadable(final long usage) {
    if (usage < KILOBYTE) {
      return usage + " B";
    }

    int unit = UNIT_SIZES.length - 1;
    while (usage < UNIT_SIZES[unit]) {
      unit--;
    }

    BigDecimal scaled = scale(usage, unit);

    // Rounding up can push a value just below the next unit to 1024.00; show it in that unit.
    if (unit < UNIT_SIZES.length - 1 && scaled.compareTo(NEXT_UNIT_THRESHOLD) >= 0) {
      unit++;
      scaled = scale(usage, unit);
    }

    return scaled + " " + UNIT_NAMES[unit];
  }

  private static BigDecimal scale(final long usage, final int unit) {
    return BigDecimal.valueOf(usage)
        .divide(BigDecimal.valueOf(UNIT_SIZES[unit]), 2, RoundingMode.CEILING);
  }
}
