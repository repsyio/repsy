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
package io.repsy.protocols.golang.shared.utils;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@UtilityClass
@NullMarked
public class GoVersionUtils {

  // Captures: major, minor, patch, pre-release (before '+'), build metadata (ignored)
  private static final Pattern SEMVER_PATTERN =
      Pattern.compile("v(\\d+)\\.(\\d+)\\.(\\d+)(-[^+]*)?(\\+.*)?");

  // Pseudo-version pre-release contains a 14-digit timestamp followed by a 12+ hex commit hash
  // e.g. "-20230101000000-abcdef123456" or "-0.20230101000000-abcdef123456"
  private static final Pattern PSEUDO_PRE_PATTERN = Pattern.compile(".*?(\\d{14})-[0-9a-f]{12,}$");

  private static final int SEMVER_MAJOR_GROUP = 1;
  private static final int SEMVER_PATCH_GROUP = 3;
  private static final int SEMVER_PRE_RELEASE_GROUP = 4;

  /**
   * Comparator for Go semver strings (e.g. "v1.2.3"). Follows SemVer precedence: pre-release
   * versions (e.g. "v1.0.0-beta") sort <em>before</em> the corresponding release for the same
   * major.minor.patch (i.e. v1.0.0-beta &lt; v1.0.0). Falls back to lexicographic comparison for
   * non-standard version strings.
   */
  public static final Comparator<String> COMPARATOR =
      (v1, v2) -> {
        final var m1 = SEMVER_PATTERN.matcher(v1);
        final var m2 = SEMVER_PATTERN.matcher(v2);

        if (!m1.matches() || !m2.matches()) {
          return v1.compareTo(v2);
        }

        for (int i = SEMVER_MAJOR_GROUP; i <= SEMVER_PATCH_GROUP; i++) {
          final int diff = Long.compare(Long.parseLong(m1.group(i)), Long.parseLong(m2.group(i)));
          if (diff != 0) {
            return diff;
          }
        }

        return comparePreRelease(
            m1.group(SEMVER_PRE_RELEASE_GROUP), m2.group(SEMVER_PRE_RELEASE_GROUP));
      };

  private static final Set<String> VERSIONED_EXTENSIONS = Set.of(".zip", ".mod", ".info");

  /**
   * Extracts the version string from the last path segment, stripping any known extension (.zip,
   * .mod, .info). E.g. "/io.repsy/hello-world/@v/v1.2.0.zip" → "v1.2.0"
   */
  public static String extractVersionFromPath(final String path) {
    final var segment = path.substring(path.lastIndexOf('/') + 1);
    for (final var ext : VERSIONED_EXTENSIONS) {
      if (segment.endsWith(ext)) {
        return segment.substring(0, segment.length() - ext.length());
      }
    }
    return segment;
  }

  /**
   * Extracts the module path from a Go proxy URL path. E.g. "/github.com/foo/bar/@v/v1.2.3.zip" →
   * "github.com/foo/bar" Returns null if the path does not contain "/@v/".
   */
  public static @Nullable String extractModulePath(final String path) {
    final int atVIndex = path.indexOf("/@v/");
    if (atVIndex < 0) {
      return null;
    }
    return path.substring(1, atVIndex); // strip leading '/'
  }

  /**
   * Parses the "go X.Y" directive from a go.mod file's raw bytes. Returns null if the directive is
   * absent. Trailing tokens on the same line (e.g. comments) are ignored so that valid go.mod files
   * with inline annotations are handled correctly.
   */
  public static @Nullable String extractGoVersionFromMod(final byte[] content) {
    final var text = new String(content, StandardCharsets.UTF_8);
    final var pattern = Pattern.compile("^go\\s+(\\S+)", Pattern.MULTILINE);
    final var matcher = pattern.matcher(text);
    return matcher.find() ? matcher.group(1) : null;
  }

  /**
   * Decodes Go's uppercase-escape encoding ("!x" → "X") used in module paths. E.g.
   * "github.com/!burnt!sushi/toml" → "github.com/BurntSushi/toml"
   */
  public static String decodeModulePath(final String encoded) {
    final var sb = new StringBuilder(encoded.length());
    boolean escape = false;

    for (final char c : encoded.toCharArray()) {
      if (escape) {
        sb.append(Character.toUpperCase(c));
        escape = false;
      } else if (c == '!') {
        escape = true;
      } else {
        sb.append(c);
      }
    }

    return sb.toString();
  }

  /**
   * Compares pre-release suffixes following SemVer and Go module precedence:
   *
   * <ul>
   *   <li>A release (null suffix) is greater than any pre-release with the same major.minor.patch.
   *   <li>Pseudo-versions (e.g. {@code -20230101000000-abcdef123456}) are compared by their
   *       embedded timestamp, not lexicographically.
   *   <li>All other pre-release strings fall back to lexicographic comparison.
   * </ul>
   */
  private static int comparePreRelease(final @Nullable String pre1, final @Nullable String pre2) {
    if (pre1 == null) {
      return pre2 == null
          ? 0
          : 1; // release > pre-release (SemVer: pre-release has lower precedence)
    }
    if (pre2 == null) {
      return -1; // pre-release < release
    }
    return compareBothPresent(pre1, pre2);
  }

  // Compares two non-null pre-release strings: pseudo-versions by timestamp, others
  // lexicographically.
  private static int compareBothPresent(final String pre1, final String pre2) {
    final var ps1 = PSEUDO_PRE_PATTERN.matcher(pre1);
    final var ps2 = PSEUDO_PRE_PATTERN.matcher(pre2);
    if (ps1.matches() && ps2.matches()) {
      return ps1.group(1).compareTo(ps2.group(1));
    }
    return pre1.compareTo(pre2);
  }
}
