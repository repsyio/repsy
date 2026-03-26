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
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Getter
public class ReleaseVersion {
  // https://www.python.org/dev/peps/pep-0440/#appendix-b-parsing-version-strings-with-regular-expressions
  private static final @NonNull Pattern VERSION_PATTERN =
      Pattern.compile(
          "^v?(?:(?:(?<epoch>[0-9]+)!)?"
              + "(?<release>[0-9]+(?:\\.[0-9]+)*)"
              + "(?<pre>[-_.]?(?<preSignifier>(?:alpha|a|beta|b|rc|c|preview|pre))[-_.]?(?<preNumeral>[0-9]+)?)?"
              + "(?<post>(?:-(?<postNumeral1>[0-9]+))|(?:[-_.]?(post|rev|r)[-_.]?(?<postNumeral2>[0-9]+)?))?"
              + "(?<dev>[-_.]?(dev)[-_.]?(?<devNumeral>[0-9]+)?)?)(?:\\+([a-z0-9]+(?:[-_.][a-z0-9]+)*))?$");
  private static final @NonNull Pattern NORMALIZED_VERSION_PATTERN =
      Pattern.compile(
          "^([1-9][0-9]*!)?(0|[1-9][0-9]*)"
              + "(\\.(0|[1-9][0-9]*))*(?<pre>(a|b|rc)(0|[1-9][0-9]*))?(?<post>\\.post(0|[1-9][0-9]*))?"
              + "(?<dev>\\.dev(0|[1-9][0-9]*))?$");

  private boolean isPreRelease;
  private boolean isPostRelease;
  private boolean isDevelopmentRelease;
  private String version;

  public static @NonNull ReleaseVersion of(final @NonNull String releaseVersion) {

    // version strings must be trimmed and lower-cased before processing
    final var trimmedVersion = releaseVersion.trim().toLowerCase(Locale.getDefault());
    final var normalizedVersionMatcher = NORMALIZED_VERSION_PATTERN.matcher(trimmedVersion);

    if (normalizedVersionMatcher.matches()) {
      final ReleaseVersion rv = new ReleaseVersion();

      rv.isPreRelease = normalizedVersionMatcher.group("pre") != null;
      rv.isPostRelease = normalizedVersionMatcher.group("post") != null;
      rv.isDevelopmentRelease = normalizedVersionMatcher.group("dev") != null;
      rv.version = trimmedVersion;

      return rv;
    }

    // fallback to un-normalized version
    final var versionMatcher = VERSION_PATTERN.matcher(trimmedVersion);

    if (!versionMatcher.matches()) {
      throw new BadRequestException("badVersionString");
    }

    final var rv = new ReleaseVersion();

    rv.isPreRelease = versionMatcher.group("pre") != null;
    rv.isPostRelease = versionMatcher.group("post") != null;
    rv.isDevelopmentRelease = versionMatcher.group("dev") != null;

    rv.normalize(
        versionMatcher.group("epoch"),
        versionMatcher.group("release"),
        versionMatcher.group("preSignifier"),
        versionMatcher.group("preNumeral"),
        versionMatcher.group("postNumeral1"),
        versionMatcher.group("postNumeral2"),
        versionMatcher.group("devNumeral"));

    return rv;
  }

  public boolean isFinalRelease() {
    return !this.isPreRelease && !this.isPostRelease && !this.isDevelopmentRelease;
  }

  private void normalize(
      final @Nullable String epoch,
      final @Nullable String release,
      final @Nullable String preSignifier,
      final @Nullable String preNumeral,
      final @Nullable String postNumeral1,
      final @Nullable String postNumeral2,
      final @Nullable String devNumeral) {

    final var normalizedVersionBuilder = new StringBuilder();

    if (epoch != null) {
      normalizedVersionBuilder.append(epoch).append("!");
    }

    normalizedVersionBuilder.append(release);

    if (this.isPreRelease && preSignifier != null) {
      normalizedVersionBuilder.append(this.resolvePreSignifier(preSignifier));
      normalizedVersionBuilder.append(Objects.requireNonNullElse(preNumeral, "0"));
    }

    if (this.isPostRelease) {
      normalizedVersionBuilder.append(".post");
      normalizedVersionBuilder.append(
          Objects.requireNonNullElseGet(
              postNumeral1, () -> Objects.requireNonNullElse(postNumeral2, "0")));
    }

    if (this.isDevelopmentRelease) {
      normalizedVersionBuilder.append(".dev");
      normalizedVersionBuilder.append(Objects.requireNonNullElse(devNumeral, "0"));
    }

    this.version = normalizedVersionBuilder.toString();
  }

  private @NonNull String resolvePreSignifier(final @NonNull String preSignifier) {

    if (preSignifier.equals("a") || preSignifier.equals("alpha")) {
      return "a";

    } else if (preSignifier.equals("b") || preSignifier.equals("beta")) {
      return "b";

    } else { // rc c pre preview

      return "rc";
    }
  }
}
