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
package io.repsy.protocols.npm.shared.utils;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A semantic version parsed and compared the way real npm (built on node-semver) does, unlike
 * {@code com.vdurmont.semver4j} (RPS-1208): major, minor and patch are arbitrary-precision numeric
 * identifiers, not a Java {@code Integer}, so a version such as {@code 2147483648.0.0} (one part
 * above {@code Integer.MAX_VALUE}) parses and compares correctly instead of being refused.
 *
 * <p>The grammar is the official <a
 * href="https://semver.org/#semantic-versioning-specification-semver">semver.org regular
 * expression</a>, which node-semver itself is built on: {@code
 * MAJOR.MINOR.PATCH[-PRERELEASE][+BUILD]}, where each of major, minor and patch is {@code 0} or a
 * digit string with no leading zero. Precedence follows semver.org &sect;11: major, then minor,
 * then patch, numerically; a version with a pre-release has lower precedence than the same version
 * without one; pre-release identifiers are compared left to right, numeric identifiers compare
 * numerically and always sort before alphanumeric ones, and a larger set of fields has higher
 * precedence when all the shared fields are equal. Build metadata plays no part in precedence.
 */
public final class NpmSemver implements Comparable<NpmSemver> {

  private static final int CORE_PARTS = 3;

  // The grammar of semver.org, checked piece by piece instead of by one regular expression: a
  // single expression is too complex to maintain and its repeated alternation can overflow the
  // stack on a long input.
  private static final Pattern NUMERIC_PART = Pattern.compile("0|[1-9]\\d*");
  private static final Pattern PRE_RELEASE =
      Pattern.compile("0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*");
  private static final Pattern BUILD = Pattern.compile("[0-9a-zA-Z-]+");

  private final BigInteger major;
  private final BigInteger minor;
  private final BigInteger patch;

  /** Dot-separated pre-release identifiers, in order; empty when the version has none. */
  private final String[] preRelease;

  private NpmSemver(
      final BigInteger major,
      final BigInteger minor,
      final BigInteger patch,
      final String[] preRelease) {
    this.major = major;
    this.minor = minor;
    this.patch = patch;
    this.preRelease = preRelease;
  }

  /** Whether {@code version} matches the semver grammar, without throwing. */
  public static boolean isValid(final String version) {
    return tryParse(version) != null;
  }

  /**
   * Parses a strict {@code MAJOR.MINOR.PATCH[-PRERELEASE][+BUILD]} version.
   *
   * @throws BadRequestException With the fixed {@code invalidPackageVersion} id when {@code
   *     version} does not match the grammar.
   */
  public static NpmSemver parse(final String version) {
    final var parsed = tryParse(version);

    if (parsed == null) {
      throw new BadRequestException(ProtocolErrorCodes.INVALID_PACKAGE_VERSION);
    }

    return parsed;
  }

  private static @Nullable NpmSemver tryParse(final String version) {
    final var buildStart = version.indexOf('+');

    if (buildStart < 0) {
      return parseWithoutBuild(version);
    }

    if (splitIdentifiers(version.substring(buildStart + 1), BUILD) == null) {
      return null;
    }

    return parseWithoutBuild(version.substring(0, buildStart));
  }

  private static @Nullable NpmSemver parseWithoutBuild(final String version) {
    final var preReleaseStart = version.indexOf('-');
    final var coreText = preReleaseStart < 0 ? version : version.substring(0, preReleaseStart);
    final var core = splitIdentifiers(coreText, NUMERIC_PART);

    if (core == null || core.length != CORE_PARTS) {
      return null;
    }

    final var preRelease =
        preReleaseStart < 0
            ? new String[0]
            : splitIdentifiers(version.substring(preReleaseStart + 1), PRE_RELEASE);

    if (preRelease == null) {
      return null;
    }

    return new NpmSemver(
        new BigInteger(core[0]), new BigInteger(core[1]), new BigInteger(core[2]), preRelease);
  }

  /** The dot-separated identifiers of {@code text}, or {@code null} when one does not match. */
  private static String @Nullable [] splitIdentifiers(final String text, final Pattern identifier) {
    final var parts = text.split("\\.", -1);

    for (final var part : parts) {
      if (!identifier.matcher(part).matches()) {
        return null;
      }
    }

    return parts;
  }

  @Override
  public int compareTo(final NpmSemver other) {
    var cmp = this.major.compareTo(other.major);
    if (cmp != 0) {
      return cmp;
    }

    cmp = this.minor.compareTo(other.minor);
    if (cmp != 0) {
      return cmp;
    }

    cmp = this.patch.compareTo(other.patch);
    if (cmp != 0) {
      return cmp;
    }

    return comparePreRelease(this.preRelease, other.preRelease);
  }

  /** Equal when {@link #compareTo} says so: build metadata is not part of a version's identity. */
  @Override
  public boolean equals(final @Nullable Object other) {
    return this == other || (other instanceof final NpmSemver that && this.compareTo(that) == 0);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.major, this.minor, this.patch, Arrays.hashCode(this.preRelease));
  }

  private static int comparePreRelease(final String[] a, final String[] b) {
    if (a.length == 0 || b.length == 0) {
      return comparePreReleasePresence(a.length, b.length);
    }

    final var sharedLength = Math.min(a.length, b.length);

    for (var i = 0; i < sharedLength; i++) {
      final var cmp = compareIdentifier(a[i], b[i]);
      if (cmp != 0) {
        return cmp;
      }
    }

    // semver.org #11.4.4: a larger set of fields has higher precedence when all the shared ones
    // are equal.
    return Integer.compare(a.length, b.length);
  }

  /** semver.org #11.4: a version without a pre-release has higher precedence than one with. */
  private static int comparePreReleasePresence(final int aLength, final int bLength) {
    if (aLength == bLength) {
      return 0; // both empty; a non-empty length here always differs from an empty one.
    }
    return aLength == 0 ? 1 : -1;
  }

  private static int compareIdentifier(final String a, final String b) {
    final var aNumeric = isNumericIdentifier(a);
    final var bNumeric = isNumericIdentifier(b);

    if (aNumeric && bNumeric) {
      return new BigInteger(a).compareTo(new BigInteger(b));
    }
    if (aNumeric) {
      return -1; // semver.org #11.4.3: numeric identifiers always have lower precedence.
    }
    if (bNumeric) {
      return 1;
    }

    return a.compareTo(b);
  }

  private static boolean isNumericIdentifier(final String identifier) {
    for (var i = 0; i < identifier.length(); i++) {
      if (!Character.isDigit(identifier.charAt(i))) {
        return false;
      }
    }
    return true;
  }
}
