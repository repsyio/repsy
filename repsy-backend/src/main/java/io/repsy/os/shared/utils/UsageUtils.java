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

  private UsageUtils() {
    throw new UnsupportedOperationException("Utility class");
  }

  public static @NonNull String humanReadable(final long usage) {
    if (usage >= TERABYTE) {
      return BigDecimal.valueOf(usage).divide(BigDecimal.valueOf(TERABYTE), 2, RoundingMode.CEILING)
          + " TB";
    } else if (usage >= GIGABYTE) {
      return BigDecimal.valueOf(usage).divide(BigDecimal.valueOf(GIGABYTE), 2, RoundingMode.CEILING)
          + " GB";
    } else if (usage >= MEGABYTE) {
      return BigDecimal.valueOf(usage).divide(BigDecimal.valueOf(MEGABYTE), 2, RoundingMode.CEILING)
          + " MB";
    } else if (usage >= KILOBYTE) {
      return BigDecimal.valueOf(usage).divide(BigDecimal.valueOf(KILOBYTE), 2, RoundingMode.CEILING)
          + " KB";
    }

    return usage + " B";
  }
}
