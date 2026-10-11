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
package io.repsy.protocols.pypi.shared.utils;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A PEP 440 version, ordered by <a
 * href="https://packaging.python.org/en/latest/specifications/version-specifiers/#summary-of-permitted-suffixes-and-relative-ordering">its
 * precedence rules</a>, the way the reference {@code packaging} library's {@code Version._cmpkey}
 * does (RPS-1688, the PyPI counterpart of Maven's {@code ComparableVersion}-based fix from
 * RPS-1665). {@link ReleaseVersion} already parses and normalizes a version for storage, but its
 * normalized string does not sort correctly as plain text either: a release segment sorts as a
 * string there ({@code "10.0"} would sit above {@code "9.0"}), and a pre-release like {@code
 * "2.0.0a1"} sorts after the final release {@code "2.0.0"} it precedes because it is the longer
 * string. This class parses the same grammar, local segment included, into comparable fields
 * instead.
 *
 * <p>A stored release version may carry a normalized local segment (the optional {@code +...}
 * suffix, RPS-1662), and the comparator orders it following PEP 440: a version with a local segment
 * sorts after the same version without one, and same-position segments compare numeric-to-numeric
 * and string-to-string, with a numeric segment always outranking a string one.
 */
public final class Pep440Version implements Comparable<Pep440Version> {

  // The official `packaging` library's VERSION_PATTERN (PEP 440 Appendix B), reproduced
  // structurally so the parsed groups map onto Version._cmpkey's fields. No group nests a
  // quantifier inside another quantified group, so matching stays linear in the input length.
  private static final Pattern PATTERN =
      Pattern.compile(
          "^\\s*v?"
              + "(?:(?<epoch>[0-9]+)!)?"
              + "(?<release>[0-9]+(?:\\.[0-9]+)*)"
              + "(?:[-_.]?(?<prelabel>alpha|a|beta|b|preview|pre|c|rc)[-_.]?(?<prenum>[0-9]+)?)?"
              + "(?:(?:-(?<postnum1>[0-9]+))"
              + "|(?:[-_.]?(?<postlabel>post|rev|r)[-_.]?(?<postnum2>[0-9]+)?))?"
              + "(?:[-_.]?(?<devlabel>dev)[-_.]?(?<devnum>[0-9]+)?)?"
              + "(?:\\+(?<local>[a-z0-9]+(?:[-_.][a-z0-9]+)*))?"
              + "\\s*$");

  private static final Pattern RELEASE_SEPARATOR = Pattern.compile("\\.");
  private static final Pattern LOCAL_SEPARATOR = Pattern.compile("[-_.]");

  /** Pre-release label rank: alpha < beta < release-candidate. Absent-with-dev sorts before all. */
  private static final int PRE_RANK_DEV_ONLY = -1;

  private static final int PRE_RANK_ALPHA = 0;
  private static final int PRE_RANK_BETA = 1;
  private static final int PRE_RANK_RC = 2;

  /** No pre-release (and not a bare dev release): sorts after every real pre-release. */
  private static final int PRE_RANK_NONE = 3;

  /**
   * The precedence order (PEP 440's {@code Version._cmpkey}): epoch, then release, then pre-release
   * rank and number (always compared, meaningless-but-equal when absent since {@link #extractPre}
   * defaults an absent pre-release's number to zero on both sides), then whether there is a
   * post-release and its number, then whether there is a dev-release (present sorts first) and its
   * number, then the local version.
   */
  private static final Comparator<Pep440Version> ORDER =
      Comparator.comparing((Pep440Version v) -> v.epoch)
          .thenComparing((a, b) -> compareSegments(a.release, b.release))
          .thenComparingInt(v -> v.preRank)
          .thenComparing(v -> v.preNum)
          .thenComparing(v -> v.hasPost)
          .thenComparing(v -> v.postNum)
          .thenComparing(v -> !v.hasDev)
          .thenComparing(v -> v.devNum)
          .thenComparing((a, b) -> compareLocal(a.local, b.local));

  private final BigInteger epoch;
  private final List<BigInteger> release;
  private final int preRank;
  private final BigInteger preNum;
  private final boolean hasPost;
  private final BigInteger postNum;
  private final boolean hasDev;
  private final BigInteger devNum;
  private final @Nullable List<LocalSegment> local;

  private Pep440Version(
      final BigInteger epoch,
      final List<BigInteger> release,
      final int preRank,
      final BigInteger preNum,
      final boolean hasPost,
      final BigInteger postNum,
      final boolean hasDev,
      final BigInteger devNum,
      final @Nullable List<LocalSegment> local) {
    this.epoch = epoch;
    this.release = release;
    this.preRank = preRank;
    this.preNum = preNum;
    this.hasPost = hasPost;
    this.postNum = postNum;
    this.hasDev = hasDev;
    this.devNum = devNum;
    this.local = local;
  }

  /**
   * Parses a PEP 440 version string.
   *
   * @throws IllegalArgumentException when {@code version} does not match the grammar. Every stored
   *     PyPI release version was normalized by {@link ReleaseVersion#of} at publish time, so this
   *     is not expected for data already in the database.
   */
  public static Pep440Version parse(final String version) {

    final var trimmed = version.strip().toLowerCase(Locale.ROOT);
    final var matcher = PATTERN.matcher(trimmed);

    if (!matcher.matches()) {
      throw new IllegalArgumentException("not a PEP 440 version: " + version);
    }

    final var epoch = extractEpoch(matcher);
    final var release = parseRelease(matcher.group("release"));
    final var pre = extractPre(matcher);
    final var post = extractPost(matcher);
    final var dev = extractDev(matcher);
    final var preRank = computePreRank(matcher.group("prelabel"), dev.present(), post.present());
    final var local = extractLocal(matcher);

    return new Pep440Version(
        epoch,
        release,
        preRank,
        pre.num(),
        post.present(),
        post.num(),
        dev.present(),
        dev.num(),
        local);
  }

  /** A version segment's presence and, if present, its number (zero when the grammar omits it). */
  private record Segment(boolean present, BigInteger num) {}

  private static BigInteger extractEpoch(final Matcher matcher) {
    final var epoch = matcher.group("epoch");
    return epoch == null ? BigInteger.ZERO : new BigInteger(epoch);
  }

  private static Segment extractPre(final Matcher matcher) {
    final var label = matcher.group("prelabel");
    if (label == null) {
      return new Segment(false, BigInteger.ZERO);
    }
    final var num = matcher.group("prenum");
    return new Segment(true, num == null ? BigInteger.ZERO : new BigInteger(num));
  }

  private static Segment extractPost(final Matcher matcher) {
    final var num1 = matcher.group("postnum1");
    final var label = matcher.group("postlabel");
    if (num1 == null && label == null) {
      return new Segment(false, BigInteger.ZERO);
    }
    final var raw = num1 != null ? num1 : matcher.group("postnum2");
    return new Segment(true, raw == null ? BigInteger.ZERO : new BigInteger(raw));
  }

  private static Segment extractDev(final Matcher matcher) {
    final var label = matcher.group("devlabel");
    if (label == null) {
      return new Segment(false, BigInteger.ZERO);
    }
    final var num = matcher.group("devnum");
    return new Segment(true, num == null ? BigInteger.ZERO : new BigInteger(num));
  }

  private static @Nullable List<LocalSegment> extractLocal(final Matcher matcher) {
    final var group = matcher.group("local");
    return group == null ? null : parseLocal(group);
  }

  /**
   * The pre-release rank a version's precedence key sorts by: a real pre-release's label rank, or
   * one of the two sentinels a plain PEP 440 comparison uses for "no pre-release" ({@link
   * #PRE_RANK_DEV_ONLY} when the version is only a dev-release of the final version, sorting before
   * every real pre-release; {@link #PRE_RANK_NONE} otherwise, sorting after all of them).
   */
  private static int computePreRank(
      final @Nullable String preLabel, final boolean hasDev, final boolean hasPost) {
    if (preLabel != null) {
      return preRank(preLabel);
    }
    if (hasDev && !hasPost) {
      return PRE_RANK_DEV_ONLY;
    }
    return PRE_RANK_NONE;
  }

  private static List<BigInteger> parseRelease(final String release) {
    final var parts = RELEASE_SEPARATOR.splitAsStream(release).toList();
    final var segments = new ArrayList<BigInteger>(parts.size());
    for (final var part : parts) {
      segments.add(new BigInteger(part));
    }
    // PEP 440: trailing zero release segments are not significant ("1.0" == "1.0.0"); strip them
    // so the remaining tuple compares correctly against a release with a genuinely shorter tuple.
    var end = segments.size();
    while (end > 1 && segments.get(end - 1).equals(BigInteger.ZERO)) {
      end--;
    }
    return segments.subList(0, end);
  }

  private static int preRank(final String label) {
    return switch (label) {
      case "a", "alpha" -> PRE_RANK_ALPHA;
      case "b", "beta" -> PRE_RANK_BETA;
      default -> PRE_RANK_RC; // c, rc, pre, preview
    };
  }

  private static List<LocalSegment> parseLocal(final String local) {
    final var parts = LOCAL_SEPARATOR.splitAsStream(local).toList();
    final var segments = new ArrayList<LocalSegment>(parts.size());
    for (final var part : parts) {
      segments.add(
          part.chars().allMatch(Character::isDigit)
              ? new LocalSegment(new BigInteger(part), null)
              : new LocalSegment(null, part));
    }
    return segments;
  }

  @Override
  public int compareTo(final Pep440Version other) {
    return ORDER.compare(this, other);
  }

  /** Two release or local-version segment tuples, in PEP 440's tuple order. */
  private static <T extends Comparable<T>> int compareSegments(final List<T> a, final List<T> b) {
    final var shared = Math.min(a.size(), b.size());
    for (var i = 0; i < shared; i++) {
      final var cmp = a.get(i).compareTo(b.get(i));
      if (cmp != 0) {
        return cmp;
      }
    }
    return Integer.compare(a.size(), b.size());
  }

  /** A version without a local segment sorts before the same version with one. */
  private static int compareLocal(
      final @Nullable List<LocalSegment> a, final @Nullable List<LocalSegment> b) {

    if (a == null || b == null) {
      return Boolean.compare(a != null, b != null);
    }
    return compareSegments(a, b);
  }

  /** One dot-separated local-version identifier: a number, or a lower-cased string. */
  private record LocalSegment(@Nullable BigInteger number, @Nullable String text)
      implements Comparable<LocalSegment> {

    @Override
    public int compareTo(final LocalSegment other) {
      // PEP 440: numeric segments always outrank alphanumeric ones at the same position.
      if (this.number != null && other.number != null) {
        return this.number.compareTo(other.number);
      }
      if (this.number != null) {
        return 1;
      }
      if (other.number != null) {
        return -1;
      }
      return Objects.requireNonNull(this.text).compareTo(Objects.requireNonNull(other.text));
    }
  }
}
