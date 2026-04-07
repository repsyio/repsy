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
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@UtilityClass
@NullMarked
public class GoVersionUtils {

  private static final Pattern SEMVER_PATTERN = Pattern.compile("v(\\d+)\\.(\\d+)\\.(\\d+)(-.*)?");

  private static final int SEMVER_MAJOR_GROUP = 1;
  private static final int SEMVER_PATCH_GROUP = 3;
  private static final int SEMVER_PRE_RELEASE_GROUP = 4;

  /**
   * Comparator for Go semver strings (e.g. "v1.2.3"). Pre-release versions (e.g. "v1.0.0-beta")
   * sort after the corresponding release for the same major.minor.patch. Falls back to
   * lexicographic comparison for non-standard version strings.
   */
  public static final Comparator<String> COMPARATOR =
      (v1, v2) -> {
        final var m1 = SEMVER_PATTERN.matcher(v1);
        final var m2 = SEMVER_PATTERN.matcher(v2);

        if (!m1.matches() || !m2.matches()) {
          return v1.compareTo(v2);
        }

        for (int i = SEMVER_MAJOR_GROUP; i <= SEMVER_PATCH_GROUP; i++) {
          final int diff = Integer.parseInt(m1.group(i)) - Integer.parseInt(m2.group(i));
          if (diff != 0) {
            return diff;
          }
        }

        return comparePreRelease(
            m1.group(SEMVER_PRE_RELEASE_GROUP), m2.group(SEMVER_PRE_RELEASE_GROUP));
      };

  /**
   * Extracts the version string from the last path segment (no extension stripping). E.g.
   * "/corp.internal/payments/@v/v1.2.0" → "v1.2.0"
   */
  public static String extractVersionFromPath(final String path) {
    return path.substring(path.lastIndexOf('/') + 1);
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
   * Pre-release versions sort after (are greater than) the corresponding release. Both null →
   * equal; one null → null (release) is smaller; both non-null → lexicographic.
   */
  private static int comparePreRelease(final @Nullable String pre1, final @Nullable String pre2) {

    if (pre1 == null && pre2 == null) {
      return 0;
    }
    if (pre1 == null) {
      return 1; // release > pre-release (SemVer: pre-release has lower precedence)
    }
    if (pre2 == null) {
      return -1; // pre-release < release (SemVer: pre-release has lower precedence)
    }
    return pre1.compareTo(pre2);
  }
}
