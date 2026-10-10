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

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.nuget.protocol.facades.dtos.PackageIdVersion;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The column limits of the NuGet package tables, the paging parameter and package path readers of
 * the protocol handlers, and the repo gate and stream copy of a publish.
 */
@NullMarked
@UtilityClass
public final class NuGetPackageUtils {

  // The limits of the nuget_package and nuget_package_version varchar columns (V0005 migration).
  // They are the single place for these numbers: the guards below cut, drop or reject a value at
  // the limit, and the NuGetPackage and NuGetPackageVersion entities give the same value to
  // @Column(length). Where H2 and PostgreSQL differ (tags is text in PostgreSQL, varchar(1024) in
  // H2) the smaller one applies. NuGetSchemaAnnotationIT fails when one drifts from the schema.

  /** The {@code nuget_package.package_id} column. The id pattern accepts at most 100 characters. */
  public static final int MAX_PACKAGE_ID_LENGTH = 256;

  /** The {@code nuget_package_version.version} column. */
  public static final int MAX_VERSION_LENGTH = 64;

  /** The {@code nuget_package_version.title} column. */
  public static final int MAX_TITLE_LENGTH = 512;

  /** The {@code nuget_package_version.tags} column (PostgreSQL: text, H2: varchar(1024)). */
  public static final int MAX_TAGS_LENGTH = 1024;

  /** The {@code icon_url}, {@code license_url}, {@code project_url} and {@code repository_url}. */
  public static final int MAX_URL_LENGTH = 512;

  /**
   * Upper bound the search and autocomplete endpoints clamp {@code take} to (nuget.org's own
   * convention: a request for more gets this many results instead of an error). Large enough for
   * any real client page, small enough that one request cannot read a whole repo's package listing
   * into memory.
   */
  public static final int MAX_SEARCH_TAKE = 1000;

  private static final int THREE = 3;
  private static final int FOUR = 4;

  /**
   * Parses the {@code skip} or {@code take} query parameter of the search and autocomplete
   * endpoints, defaulting to {@code defaultValue} when the client left it out. A value that is not
   * a non-negative integer — non-numeric, decimal, negative, or one that overflows {@code int} — is
   * a client error and throws {@link IllegalArgumentException} instead of letting {@link
   * Integer#parseInt} throw {@link NumberFormatException} out to a handler's catch-all, which would
   * answer 500 and log it as a server error.
   */
  public static int parseNonNegativeParam(
      final @Nullable String value, final int defaultValue, final String paramName) {

    if (value == null) {
      return defaultValue;
    }

    final int parsed;
    try {
      parsed = Integer.parseInt(value);
    } catch (final NumberFormatException e) {
      throw new IllegalArgumentException("'" + paramName + "' is not a valid integer: " + value, e);
    }

    if (parsed < 0) {
      throw new IllegalArgumentException("'" + paramName + "' must not be negative: " + value);
    }

    return parsed;
  }

  public static String extractPackageId(final ProtocolContext context) {
    final var path = ProtocolContextUtils.getRelativePath(context).getPath();
    final var parts = path.split("/", -1);
    return parts.length > THREE ? parts[THREE] : "";
  }

  public static PackageIdVersion extractPackageIdAndVersion(final ProtocolContext context) {
    final var path = ProtocolContextUtils.getRelativePath(context).getPath();
    final var parts = path.split("/", -1);
    return new PackageIdVersion(
        parts.length > THREE ? parts[THREE] : "", parts.length > FOUR ? parts[FOUR] : "");
  }

  public static void copyStreamToFile(final InputStream stream, final Path target)
      throws IOException {

    final long size = Files.copy(stream, target, REPLACE_EXISTING);

    if (size == 0) {
      throw new IllegalArgumentException("NuGet package stream is empty.");
    }
  }

  public static void checkVersionAllowance(final String version, final BaseRepoInfo<?> repoInfo) {

    final boolean isPrerelease = version.contains("-");
    if (isPrerelease && Boolean.FALSE.equals(repoInfo.getSnapshots())) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "Pre-release packages are not allowed in this repository.");
    }
    if (!isPrerelease && Boolean.FALSE.equals(repoInfo.getReleases())) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT, "Release packages are not allowed in this repository.");
    }
  }
}
