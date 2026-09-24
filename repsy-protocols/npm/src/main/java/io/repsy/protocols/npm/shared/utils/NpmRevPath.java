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

import java.util.Optional;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Parses the {@code -rev} paths an {@code npm unpublish} sends (RPS-1289). The npm client (see
 * {@code libnpmpublish}'s {@code unpublish}) identifies the package document it read with its
 * {@code _rev} and sends three requests, none of whose path is a bare package name:
 *
 * <ol>
 *   <li>{@code PUT /<package>/-rev/<rev>} with the packument minus the unpublished version;
 *   <li>{@code DELETE /<package>/-/<tarball>/-rev/<rev>} for the tarball of that version, the path
 *       being the version's own {@code dist.tarball} path;
 *   <li>{@code DELETE /<package>/-rev/<rev>} instead of both when it was the only version.
 * </ol>
 *
 * <p>{@code <package>} is {@code name} or {@code @scope/name}; a client escapes the slash of a
 * scoped name ({@code @scope%2fname}) and the connector decodes it, so the servlet path carries a
 * plain {@code /}.
 */
@UtilityClass
@NullMarked
public class NpmRevPath {

  /**
   * The package is one segment or {@code @scope/segment}, the tarball optionally repeats the scope,
   * and the name is never the {@code -} of the {@code /-/} marker.
   */
  private static final Pattern PATTERN =
      Pattern.compile(
          "^/(?:@(?<scope>[^/]+)/)?(?<name>(?!-/)[^/]+)"
              + "(?:/-/(?:@[^/]+/)?(?<tarball>[^/]+))?/-rev/(?<rev>[^/]+)$");

  /**
   * Parses a path relative to the repo.
   *
   * @return the parts, or empty when the path is not a {@code -rev} path
   */
  public static Optional<RevPath> parse(final String relativePath) {

    final var matcher = PATTERN.matcher(relativePath);

    if (!matcher.matches()) {
      return Optional.empty();
    }

    return Optional.of(
        new RevPath(
            matcher.group("scope"),
            matcher.group("name"),
            matcher.group("tarball"),
            matcher.group("rev")));
  }

  /**
   * The version a tarball file name denotes: {@code <name>-<version>.tgz} without the scope.
   *
   * @return the version, or empty when the file name is not the tarball of {@code packageName}
   */
  public static Optional<String> versionOfTarball(
      final String packageName, final String tarballFilename) {

    final var prefix = packageName + "-";
    final var suffix = ".tgz";

    if (!tarballFilename.startsWith(prefix)
        || !tarballFilename.endsWith(suffix)
        || tarballFilename.length() <= prefix.length() + suffix.length()) {
      return Optional.empty();
    }

    return Optional.of(
        tarballFilename.substring(prefix.length(), tarballFilename.length() - suffix.length()));
  }

  /**
   * A parsed {@code -rev} path.
   *
   * @param tarballFilename the tarball's file name, or {@code null} for the path of the package
   *     itself
   */
  public record RevPath(
      @Nullable String scopeName,
      String packageName,
      @Nullable String tarballFilename,
      String rev) {}
}
