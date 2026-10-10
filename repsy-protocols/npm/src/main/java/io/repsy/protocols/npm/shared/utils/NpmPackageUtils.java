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

import io.repsy.protocols.npm.shared.constants.NpmConstants;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Map;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@SuppressWarnings("unchecked")
@UtilityClass
@NullMarked
/** Names, file names, the clock and the latest version of an npm package. */
public final class NpmPackageUtils {
  private static final String TARBALL_EXTENSION = "tgz";

  /** The full package name a URL's scope and package name denote, e.g. {@code @scope/name}. */
  public static String buildFullName(final @Nullable String scopeName, final String packageName) {

    return scopeName == null ? packageName : "@" + scopeName + "/" + packageName;
  }

  public static String getFormattedCurrentTime() {

    final var formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    final var now = LocalDateTime.now(ZoneId.systemDefault());

    return formatter.format(now);
  }

  public static String getLatestVersion(final Map<String, Object> payload)
      throws ClassCastException {

    final var distTags = (Map<String, String>) payload.get("dist-tags");

    return distTags.get("latest");
  }

  public static String getTarballFilename(final String packageName, final String versionName) {

    return packageName + "-" + versionName + "." + TARBALL_EXTENSION;
  }

  public static String resolveLatestVersion(final Map<String, Object> metadata) {

    final var versions = (Map<String, Object>) metadata.get(NpmConstants.VERSIONS);

    return resolveLatestVersion(versions.keySet());
  }

  /** The highest of the version names by semver, or an empty string when there are none. */
  public static String resolveLatestVersion(final Collection<String> versionNames) {

    if (versionNames.isEmpty()) {
      return "";
    }

    String latestVersion = null;
    NpmSemver latestSemver = null;

    for (final var key : versionNames) {
      final var semver = NpmSemver.parse(key);

      if (latestSemver == null || semver.compareTo(latestSemver) > 0) {
        latestSemver = semver;
        latestVersion = key;
      }
    }

    return latestVersion;
  }
}
