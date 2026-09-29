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
package io.repsy.protocols.ruby.shared.utils;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;

/**
 * Orders RubyGems version strings the way {@code Gem::Version#<=>} does (RPS-1688, the RubyGems
 * counterpart of Maven's {@code ComparableVersion}-based fix from RPS-1665), instead of as plain
 * text: a database {@code ORDER BY} on the {@code version} column would sort {@code "10.0.0"} above
 * {@code "9.0.0"}. No RubyGems version parser existed anywhere in this codebase to reuse (Ruby gem
 * publishing does not itself validate or compare version numbers today), so this reimplements
 * {@code Gem::Version}'s own algorithm.
 *
 * <p>RubyGems splits a version into "canonical segments" by scanning it for runs of digits and runs
 * of letters, letting {@code '.'} and {@code '-'} (and any other non-alphanumeric character) act
 * purely as separators between them: {@code "1.9.3.a10"} becomes the segments {@code [1, 9, 3, "a",
 * 10]}. Segments are then compared position by position, missing trailing segments on the shorter
 * side counting as {@code 0}; a numeric segment always outranks a string segment at the same
 * position (which is how a pre-release suffix such as {@code "1.0.0.rc1"} sorts before the release
 * {@code "1.0.0"} it precedes: comparing their third segment, {@code "rc"} against the implicit
 * {@code 0}, a string always loses to a number).
 */
@NullMarked
public class RubyGemVersionComparator implements Comparator<String> {

  private static final Pattern SEGMENT_PATTERN = Pattern.compile("[0-9]+|[A-Za-z]+");

  @Override
  public int compare(final String v1, final String v2) {

    final var a = segments(v1);
    final var b = segments(v2);

    final var limit = Math.max(a.size(), b.size());

    for (var i = 0; i < limit; i++) {
      final var lhs = i < a.size() ? a.get(i) : BigInteger.ZERO;
      final var rhs = i < b.size() ? b.get(i) : BigInteger.ZERO;

      final var cmp = compareSegment(lhs, rhs);
      if (cmp != 0) {
        return cmp;
      }
    }

    return 0;
  }

  private static int compareSegment(final Object lhs, final Object rhs) {

    final var lhsNumeric = lhs instanceof BigInteger;
    final var rhsNumeric = rhs instanceof BigInteger;

    if (lhsNumeric && rhsNumeric) {
      return ((BigInteger) lhs).compareTo((BigInteger) rhs);
    }
    if (lhsNumeric) {
      return 1; // a numeric segment always outranks a string segment (Gem::Version rule)
    }
    if (rhsNumeric) {
      return -1;
    }
    return ((String) lhs).compareTo((String) rhs);
  }

  /** The version's alternating digit-run / letter-run segments, in order. */
  private static List<Object> segments(final String version) {

    final var segments = new ArrayList<Object>();
    final Matcher matcher = SEGMENT_PATTERN.matcher(version);

    while (matcher.find()) {
      final var token = matcher.group();
      segments.add(isDigits(token) ? new BigInteger(token) : token);
    }

    return segments;
  }

  private static boolean isDigits(final String token) {
    return token.chars().allMatch(Character::isDigit);
  }
}
