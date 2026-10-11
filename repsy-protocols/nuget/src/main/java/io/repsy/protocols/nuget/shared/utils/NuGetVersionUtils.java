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
package io.repsy.protocols.nuget.shared.utils;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.semver4j.Semver;

/** Reads, compares and normalizes NuGet version strings the way NuGet does. */
@UtilityClass
public final class NuGetVersionUtils {

  private static final int THREE = 3;
  private static final int FOUR = 4;
  private static final String ZERO_VERSION = "0.0.0";
  private static final Pattern SEMVER_LEVEL_PATTERN = Pattern.compile("^([0-9]+)(?:[.+-].*)?$");

  /**
   * Orders NuGet versions the way NuGet does (see {@link #compareVersions}), lowest first. Reverse
   * it, or take the {@code max}, to get the latest version.
   */
  public static final Comparator<String> VERSION_COMPARATOR = NuGetVersionUtils::compareVersions;

  /**
   * Orders two NuGet versions the way NuGet does: by major, minor, patch and revision (the optional
   * fourth part, numerically, a missing part counting as 0), then by pre-release label, where a
   * release sorts after its own pre-releases. Build metadata is ignored. A value that cannot be
   * parsed falls back to a case-insensitive string comparison.
   */
  static int compareVersions(final String v1, final String v2) {
    try {
      final var first = v1.strip().toLowerCase(Locale.ROOT);
      final var second = v2.strip().toLowerCase(Locale.ROOT);

      final var numeric = compareNumericParts(numericParts(first), numericParts(second));
      if (numeric != 0) {
        return numeric;
      }
      return Objects.requireNonNull(Semver.parse(ZERO_VERSION + preRelease(first)))
          .compareTo(Objects.requireNonNull(Semver.parse(ZERO_VERSION + preRelease(second))));
    } catch (final Exception e) {
      return v1.compareToIgnoreCase(v2);
    }
  }

  private static String[] numericParts(final String version) {
    final var parts = substringBefore(substringBefore(version, '+'), '-').split("\\.");
    if (parts.length > FOUR) {
      throw new IllegalArgumentException("More than four version parts: " + version);
    }
    return parts;
  }

  private static int compareNumericParts(final String[] first, final String[] second) {
    for (int i = 0; i < FOUR; i++) {
      final var result = numericPart(first, i).compareTo(numericPart(second, i));
      if (result != 0) {
        return result;
      }
    }
    return 0;
  }

  private static BigInteger numericPart(final String[] parts, final int index) {
    return index < parts.length ? new BigInteger(parts[index]) : BigInteger.ZERO;
  }

  private static String preRelease(final String version) {
    final var withoutBuild = substringBefore(version, '+');
    return withoutBuild.substring(substringBefore(withoutBuild, '-').length());
  }

  /**
   * Normalizes a NuGet version string to its canonical form: - Lowercased - Trailing zero
   * components stripped (min 3: major.minor.patch) - Build metadata ({@code +...}) dropped, since
   * NuGet ignores it when it compares versions - 1.0 → 1.0.0, 1.0.0.0 → 1.0.0, 1.0.0-Alpha →
   * 1.0.0-alpha, 1.0.0+Build → 1.0.0. The pre-release suffix is kept as is.
   */
  public static String normalizeNuGetVersion(final String rawVersion) {
    return normalize(rawVersion);
  }

  /**
   * Whether the version is SemVer 2.0.0-only, that is not a valid SemVer 1.0.0 version: it carries
   * build metadata ({@code 1.0.0+abc}) or a pre-release label with dot-separated identifiers
   * ({@code 1.0.0-beta.1}). This is nuget.org's definition, which clients that send no {@code
   * semVerLevel} rely on to be left with versions they can parse.
   */
  public static boolean isSemVer2(final String version) {
    return version.indexOf('+') >= 0
        || preRelease(version.strip().toLowerCase(Locale.ROOT)).indexOf('.') >= 0;
  }

  /**
   * Whether the {@code semVerLevel} query parameter of the search and autocomplete endpoints opts
   * in to SemVer 2.0.0 versions: a version string whose major part is 2 or more ({@code 2.0.0}). A
   * missing, blank or lower value (or one that is not a version at all) is the SemVer 1.0.0
   * default, as it is on nuget.org.
   */
  public static boolean acceptsSemVer2(final @Nullable String semVerLevel) {
    if (semVerLevel == null) {
      return false;
    }
    final var matcher = SEMVER_LEVEL_PATTERN.matcher(semVerLevel.strip());
    return matcher.matches() && new BigInteger(matcher.group(1)).compareTo(BigInteger.TWO) >= 0;
  }

  private static String normalize(final String rawVersion) {

    final var lower = rawVersion.strip().toLowerCase(Locale.ROOT);
    final var withoutBuild = substringBefore(lower, '+');

    final var core = substringBefore(withoutBuild, '-');
    final var preRelease = withoutBuild.substring(core.length());

    final var components = core.split("\\.");

    int end = components.length;
    while (end > THREE && "0".equals(components[end - 1])) {
      end--;
    }

    return buildVersionString(components, end) + preRelease;
  }

  private static String substringBefore(final String value, final char separator) {
    final var idx = value.indexOf(separator);
    return idx >= 0 ? value.substring(0, idx) : value;
  }

  private static String buildVersionString(final String[] components, final int end) {
    final var sb = new StringBuilder();
    final int partCount = Math.max(end, 3);
    for (int i = 0; i < partCount; i++) {
      if (i > 0) {
        sb.append('.');
      }
      sb.append(i < components.length ? components[i] : "0");
    }
    return sb.toString();
  }
}
