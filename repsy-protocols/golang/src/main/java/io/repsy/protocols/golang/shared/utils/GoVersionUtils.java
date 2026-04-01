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

  private static final Pattern SEMVER_PATTERN =
      Pattern.compile("v(\\d+)\\.(\\d+)\\.(\\d+).*");

  /**
   * Comparator for Go semver strings (e.g. "v1.2.3"). Falls back to lexicographic
   * comparison for non-standard version strings.
   */
  public static final Comparator<String> COMPARATOR =
      (v1, v2) -> {
        final var m1 = SEMVER_PATTERN.matcher(v1);
        final var m2 = SEMVER_PATTERN.matcher(v2);

        if (!m1.matches() || !m2.matches()) {
          return v1.compareTo(v2);
        }

        for (int i = 1; i <= 3; i++) {
          final int diff = Integer.parseInt(m1.group(i)) - Integer.parseInt(m2.group(i));
          if (diff != 0) {
            return diff;
          }
        }

        return 0;
      };

  /**
   * Extracts the version string from a Go module zip path.
   * E.g. "/github.com/foo/bar/@v/v1.2.3.zip" → "v1.2.3"
   */
  public static String extractVersionFromZipPath(final String zipPath) {
    final var filename = zipPath.substring(zipPath.lastIndexOf('/') + 1);
    return filename.substring(0, filename.length() - ".zip".length());
  }

  /**
   * Extracts the module path from a Go proxy URL path.
   * E.g. "/github.com/foo/bar/@v/v1.2.3.zip" → "github.com/foo/bar"
   * Returns null if the path does not contain "/@v/".
   */
  public static @Nullable String extractModulePath(final String path) {
    final int atVIndex = path.indexOf("/@v/");
    if (atVIndex < 0) {
      return null;
    }
    return path.substring(1, atVIndex); // strip leading '/'
  }

  /**
   * Extracts the version from a Go .mod proxy path.
   * E.g. "/github.com/foo/bar/@v/v1.2.3.mod" → "v1.2.3"
   */
  public static String extractVersionFromModPath(final String modPath) {
    final var filename = modPath.substring(modPath.lastIndexOf('/') + 1);
    return filename.substring(0, filename.length() - ".mod".length());
  }

  /**
   * Parses the "go X.Y" directive from a go.mod file's raw bytes.
   * Returns null if the directive is absent.
   */
  public static @Nullable String extractGoVersionFromMod(final byte[] content) {
    final var text = new String(content, StandardCharsets.UTF_8);
    final var pattern = Pattern.compile("^go\\s+(\\S+)\\s*$", Pattern.MULTILINE);
    final var matcher = pattern.matcher(text);
    return matcher.find() ? matcher.group(1) : null;
  }

  /**
   * Decodes Go's uppercase-escape encoding ("!x" → "X") used in module paths.
   * E.g. "github.com/!burnt!sushi/toml" → "github.com/BurntSushi/toml"
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
}
