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
package io.repsy.protocols.ruby.protocol.handlers;

import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;

/**
 * The {@code Content-Disposition} of the Ruby routes that Spring would otherwise give {@code
 * inline;filename=f.txt}, whenever the last segment of the URL has an extension (RPS-1389,
 * RPS-1442). One place decides it, so a {@code HEAD} answers exactly what the {@code GET} it
 * mirrors does.
 *
 * <ul>
 *   <li>{@code /gems/<name>.gem}, {@code /quick/Marshal.4.8/<name>.gemspec.rz} and the three {@code
 *       *specs.4.8.gz} indexes are files: an attachment named after the last path segment;
 *   <li>{@code /info/<gem>} is text, not a file. A gem name may contain a dot ({@code foo.rb}),
 *       which Spring reads as an extension, so it says {@code inline} and names nothing;
 *   <li>{@code /names} and {@code /versions} have no extension and need nothing.
 * </ul>
 */
@NullMarked
final class RubyContentDisposition {

  private static final Pattern GEM_PATTERN = Pattern.compile("^/gems/.+\\.gem$");
  private static final Pattern GEMSPEC_PATTERN =
      Pattern.compile("^/quick/Marshal\\.4\\.8/.+\\.gemspec\\.rz$");
  private static final Set<String> SPECS_PATHS =
      Set.of("/specs.4.8.gz", "/latest_specs.4.8.gz", "/prerelease_specs.4.8.gz");
  private static final Pattern INFO_PATTERN = Pattern.compile("^/info/.+$");

  private RubyContentDisposition() {
    throw new UnsupportedOperationException("Utility class");
  }

  /** The header the {@code GET} of this path sends, or {@code null} when it sends none. */
  static @Nullable String forPath(final String relativePath) {
    if (GEM_PATTERN.matcher(relativePath).matches()
        || GEMSPEC_PATTERN.matcher(relativePath).matches()
        || SPECS_PATHS.contains(relativePath)) {
      return ContentDisposition.attachment()
          .filename(relativePath.substring(relativePath.lastIndexOf('/') + 1))
          .build()
          .toString();
    }

    if (INFO_PATTERN.matcher(relativePath).matches()) {
      return ContentDisposition.inline().build().toString();
    }

    return null;
  }
}
