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

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

// Instances are only built by of(), which sets version on every path before returning.
@SuppressWarnings("NullAway.Init")
@Getter
public class ReleaseVersion {
  // https://www.python.org/dev/peps/pep-0440/#appendix-b-parsing-version-strings-with-regular-expressions
  private static final Pattern VERSION_PATTERN =
      Pattern.compile(
          "^v?(?:(?:(?<epoch>[0-9]+)!)?"
              + "(?<release>[0-9]+(?:\\.[0-9]+)*)"
              + "(?<pre>[-_.]?(?<preSignifier>(?:alpha|a|beta|b|rc|c|preview|pre))[-_.]?(?<preNumeral>[0-9]+)?)?"
              + "(?<post>(?:-(?<postNumeral1>[0-9]+))|(?:[-_.]?(post|rev|r)[-_.]?(?<postNumeral2>[0-9]+)?))?"
              + "(?<dev>[-_.]?(dev)[-_.]?(?<devNumeral>[0-9]+)?)?)(?:\\+(?<local>[a-z0-9]+(?:[-_.][a-z0-9]+)*))?$");
  private static final Pattern NORMALIZED_VERSION_PATTERN =
      Pattern.compile(
          "^([1-9][0-9]*!)?(0|[1-9][0-9]*)"
              + "(\\.(0|[1-9][0-9]*))*(?<pre>(a|b|rc)(0|[1-9][0-9]*))?(?<post>\\.post(0|[1-9][0-9]*))?"
              + "(?<dev>\\.dev(0|[1-9][0-9]*))?(?:\\+(?<local>[a-z0-9]+(?:\\.[a-z0-9]+)*))?$");
  private static final Pattern LOCAL_SEPARATOR = Pattern.compile("[-_.]");

  private boolean preRelease;
  private boolean postRelease;
  private boolean developmentRelease;
  private String version;

  public static ReleaseVersion of(final String releaseVersion) {

    // Bound the input before either pattern runs: both nest quantifiers, so a long dotted version
    // overflows the regex engine's stack (a 500) instead of failing as a 400.
    PypiPublishLimits.checkVersion(releaseVersion);

    // version strings must be trimmed and lower-cased before processing
    final var trimmedVersion = releaseVersion.trim().toLowerCase(Locale.getDefault());
    final var normalizedVersionMatcher = NORMALIZED_VERSION_PATTERN.matcher(trimmedVersion);

    // A local segment with a number that has a leading zero is not normalized yet (+007 is +7).
    if (normalizedVersionMatcher.matches()
        && isNormalizedLocal(normalizedVersionMatcher.group("local"))) {
      final ReleaseVersion rv = new ReleaseVersion();

      rv.preRelease = normalizedVersionMatcher.group("pre") != null;
      rv.postRelease = normalizedVersionMatcher.group("post") != null;
      rv.developmentRelease = normalizedVersionMatcher.group("dev") != null;
      rv.version = trimmedVersion;

      return rv;
    }

    // fallback to un-normalized version
    final var versionMatcher = VERSION_PATTERN.matcher(trimmedVersion);

    if (!versionMatcher.matches()) {
      throw new BadRequestException(ProtocolErrorCodes.BAD_VERSION_STRING);
    }

    final var rv = new ReleaseVersion();

    rv.preRelease = versionMatcher.group("pre") != null;
    rv.postRelease = versionMatcher.group("post") != null;
    rv.developmentRelease = versionMatcher.group("dev") != null;

    rv.normalize(
        versionMatcher.group("epoch"),
        versionMatcher.group("release"),
        versionMatcher.group("preSignifier"),
        versionMatcher.group("preNumeral"),
        versionMatcher.group("postNumeral1"),
        versionMatcher.group("postNumeral2"),
        versionMatcher.group("devNumeral"),
        versionMatcher.group("local"));

    return rv;
  }

  private static boolean isNormalizedLocal(final @Nullable String local) {
    return local == null || normalizeLocal(local).equals(local);
  }

  /**
   * The PEP 440 normal form of a local version: {@code -} and {@code _} become {@code .}, and a
   * segment that is only digits loses its leading zeros. The text was lower-cased before.
   */
  private static String localSuffix(final @Nullable String local) {
    return local == null ? "" : "+" + normalizeLocal(local);
  }

  private static String normalizeLocal(final String local) {

    return LOCAL_SEPARATOR
        .splitAsStream(local)
        .map(
            segment ->
                segment.chars().allMatch(Character::isDigit) ? stripLeadingZeros(segment) : segment)
        .collect(Collectors.joining("."));
  }

  private static String stripLeadingZeros(final String digits) {

    final var stripped = digits.replaceFirst("^0+", "");

    return stripped.isEmpty() ? "0" : stripped;
  }

  public boolean isFinalRelease() {
    return !this.preRelease && !this.postRelease && !this.developmentRelease;
  }

  private void normalize(
      final @Nullable String epoch,
      final @Nullable String release,
      final @Nullable String preSignifier,
      final @Nullable String preNumeral,
      final @Nullable String postNumeral1,
      final @Nullable String postNumeral2,
      final @Nullable String devNumeral,
      final @Nullable String local) {

    final var normalizedVersionBuilder = new StringBuilder();

    if (epoch != null) {
      normalizedVersionBuilder.append(epoch).append("!");
    }

    normalizedVersionBuilder.append(release);

    if (this.preRelease && preSignifier != null) {
      normalizedVersionBuilder.append(this.resolvePreSignifier(preSignifier));
      normalizedVersionBuilder.append(Objects.requireNonNullElse(preNumeral, "0"));
    }

    if (this.postRelease) {
      normalizedVersionBuilder.append(".post");
      normalizedVersionBuilder.append(
          Objects.requireNonNullElseGet(
              postNumeral1, () -> Objects.requireNonNullElse(postNumeral2, "0")));
    }

    if (this.developmentRelease) {
      normalizedVersionBuilder.append(".dev");
      normalizedVersionBuilder.append(Objects.requireNonNullElse(devNumeral, "0"));
    }

    normalizedVersionBuilder.append(localSuffix(local));

    this.version = normalizedVersionBuilder.toString();
  }

  private String resolvePreSignifier(final String preSignifier) {

    if (preSignifier.equals("a") || preSignifier.equals("alpha")) {
      return "a";

    } else if (preSignifier.equals("b") || preSignifier.equals("beta")) {
      return "b";

    } else { // rc c pre preview

      return "rc";
    }
  }
}
