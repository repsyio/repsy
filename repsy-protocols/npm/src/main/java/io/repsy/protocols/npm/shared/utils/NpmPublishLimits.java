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

import io.repsy.core.error_handling.exceptions.BadRequestException;
import java.util.Collection;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Guards the length of every value the npm publish path stores in a length-limited column, before
 * anything is written (RPS-1136). {@code AbstractNpmProtocolFacade.publish} used to write the
 * tarball to storage (RPS-1124's ordering) and only then let {@code NpmPackageServiceImpl} copy
 * unguarded values into 17 columns; an over-long one failed the row insert with SQLSTATE 22001,
 * {@code ErrorHandler} (RPS-1012) turned that into a generic 400 that named no field, and the
 * tarball was already left behind in storage.
 *
 * <p>Three policies apply, matching the ones {@code CrateUtils}, {@code GoVersionUtils} and {@code
 * HelmConstants} already use for their own publish paths (RPS-1072):
 *
 * <ul>
 *   <li><b>Reject</b> the whole publish with a 400 naming the field: the scope, the package name,
 *       the version (and any dist-tag value, which is also a version), and a dist-tag name. These
 *       identify the package or select a release, so silently truncating or dropping them would
 *       store something under the wrong identity.
 *   <li><b>Drop</b> the value (set it to {@code null}) and keep the rest of the publish: author,
 *       bugs, homepage and repository URLs and emails, license, repository type, and a maintainer's
 *       email or url. These are descriptive only; a value cut to fit would point somewhere wrong,
 *       which is worse than a blank field.
 *   <li><b>Drop the entry</b> and keep the rest of the array: an over-long keyword, or an over-long
 *       maintainer name &mdash; {@code npm_package_maintainer.name} is {@code NOT NULL}, so unlike
 *       its email and url it cannot be nulled, and the maintainer entry is dropped whole instead.
 * </ul>
 *
 * <p>{@code npm_package_version.version} is capped at {@link #MAX_VERSION_LENGTH} (128), below the
 * column's actual {@code varchar(255)}, so that every version this guard accepts also fits {@code
 * vulnerability_scan.artifact_version} ({@code varchar(128)}) without narrowing that shared column
 * here; RPS-1140 is free to widen it independently.
 */
@SuppressWarnings("unchecked")
@NullMarked
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class NpmPublishLimits {

  // Reject: these identify the package or select a release (npm_package.scope / name,
  // npm_package_version.version, npm_package_dist_tag.tag_name).
  public static final int MAX_SCOPE_LENGTH = 214;
  public static final int MAX_NAME_LENGTH = 214;
  public static final int MAX_VERSION_LENGTH = 128;
  public static final int MAX_DIST_TAG_LENGTH = 255;

  // Drop (null): descriptive fields of npm_package_version.
  public static final int MAX_AUTHOR_NAME_LENGTH = 255;
  public static final int MAX_AUTHOR_EMAIL_LENGTH = 255;
  public static final int MAX_AUTHOR_URL_LENGTH = 255;
  public static final int MAX_BUGS_URL_LENGTH = 255;
  public static final int MAX_BUGS_EMAIL_LENGTH = 255;
  public static final int MAX_HOMEPAGE_LENGTH = 255;
  public static final int MAX_LICENSE_LENGTH = 255;
  public static final int MAX_REPOSITORY_TYPE_LENGTH = 255;
  public static final int MAX_REPOSITORY_URL_LENGTH = 255;

  // Drop (null) or drop the whole entry: npm_package_maintainer.
  public static final int MAX_MAINTAINER_NAME_LENGTH = 255;
  public static final int MAX_MAINTAINER_EMAIL_LENGTH = 255;
  public static final int MAX_MAINTAINER_URL_LENGTH = 255;

  // Drop the entry, keep the rest of the array: npm_package_keyword.
  public static final int MAX_KEYWORD_LENGTH = 255;

  /**
   * Refuses a scope or package name longer than the URL segment could ever legitimately need to be,
   * matching {@code npm_package.scope} / {@code npm_package.name}.
   *
   * @throws BadRequestException With {@code packageScopeTooLong} or {@code packageNameTooLong}.
   */
  public static void checkScopeAndName(final @Nullable String scopeName, final String packageName) {

    if (scopeName != null && scopeName.length() > MAX_SCOPE_LENGTH) {
      throw new BadRequestException("packageScopeTooLong");
    }
    if (packageName.length() > MAX_NAME_LENGTH) {
      throw new BadRequestException("packageNameTooLong");
    }
  }

  /**
   * Refuses a version name longer than {@link #MAX_VERSION_LENGTH}, matching {@code
   * npm_package_version.version}.
   *
   * @throws BadRequestException With {@code packageVersionTooLong}.
   */
  public static void checkVersion(final String versionName) {
    if (versionName.length() > MAX_VERSION_LENGTH) {
      throw new BadRequestException("packageVersionTooLong");
    }
  }

  /**
   * Refuses an over-long dist-tag name (the key, matching {@code npm_package_dist_tag.tag_name}) or
   * an over-long dist-tag value (a version string that becomes {@code npm_package.latest}).
   *
   * @throws BadRequestException With {@code distTagNameTooLong} or {@code packageVersionTooLong}.
   */
  public static void checkDistTags(final Map<String, Object> payload) {

    if (!(payload.get(NpmConstants.DIST_TAGS) instanceof final Map<?, ?> distTags)) {
      return;
    }

    for (final var entry : distTags.entrySet()) {
      checkDistTagEntry(entry.getKey(), entry.getValue());
    }
  }

  private static void checkDistTagEntry(
      final @Nullable Object tagName, final @Nullable Object version) {

    if (tagName instanceof final String tag && tag.length() > MAX_DIST_TAG_LENGTH) {
      throw new BadRequestException("distTagNameTooLong");
    }
    if (version instanceof final String value && value.length() > MAX_VERSION_LENGTH) {
      throw new BadRequestException("packageVersionTooLong");
    }
  }

  /**
   * Drops every over-long descriptive value from a single published version, in place, so what
   * reaches {@code NpmPackageServiceImpl} and the stored {@code package.json} already fits its
   * column. Keywords and maintainers whose own field cannot be nulled lose only that one entry.
   */
  public static void dropOverLongFields(final Map<String, Object> version) {

    dropStringField(version, "homepage", MAX_HOMEPAGE_LENGTH);
    dropLicense(version);
    dropAuthor(version);
    dropBugs(version);
    dropRepository(version);
    dropOverLongKeywords(version);
    dropOverLongMaintainers(version);
  }

  private static void dropLicense(final Map<String, Object> version) {
    final var license = version.get("license");

    if (license instanceof final String value && value.length() > MAX_LICENSE_LENGTH) {
      version.put("license", null);
    } else if (license instanceof final Map<?, ?> map) {
      dropStringField((Map<String, Object>) map, "type", MAX_LICENSE_LENGTH);
    }
  }

  private static void dropAuthor(final Map<String, Object> version) {
    if (!(version.get(NpmConstants.AUTHOR) instanceof final Map<?, ?> author)) {
      return;
    }

    final var map = (Map<String, Object>) author;
    dropStringField(map, NpmConstants.NAME, MAX_AUTHOR_NAME_LENGTH);
    dropStringField(map, NpmConstants.EMAIL, MAX_AUTHOR_EMAIL_LENGTH);
    dropStringField(map, NpmConstants.URL, MAX_AUTHOR_URL_LENGTH);
  }

  private static void dropBugs(final Map<String, Object> version) {
    if (!(version.get(NpmConstants.BUGS) instanceof final Map<?, ?> bugs)) {
      return;
    }

    final var map = (Map<String, Object>) bugs;
    dropStringField(map, NpmConstants.URL, MAX_BUGS_URL_LENGTH);
    dropStringField(map, NpmConstants.EMAIL, MAX_BUGS_EMAIL_LENGTH);
  }

  private static void dropRepository(final Map<String, Object> version) {
    if (!(version.get(NpmConstants.REPOSITORY) instanceof final Map<?, ?> repository)) {
      return;
    }

    final var map = (Map<String, Object>) repository;
    dropStringField(map, "type", MAX_REPOSITORY_TYPE_LENGTH);
    dropStringField(map, NpmConstants.URL, MAX_REPOSITORY_URL_LENGTH);
  }

  private static void dropOverLongKeywords(final Map<String, Object> version) {
    if (!(version.get("keywords") instanceof final Collection<?> keywords)) {
      return;
    }

    keywords.removeIf(
        keyword -> keyword instanceof final String s && s.length() > MAX_KEYWORD_LENGTH);
  }

  private static void dropOverLongMaintainers(final Map<String, Object> version) {
    if (!(version.get(NpmConstants.MAINTAINERS) instanceof final Collection<?> maintainers)) {
      return;
    }

    maintainers.removeIf(
        raw -> {
          if (!(raw instanceof final Map<?, ?> maintainer)) {
            return false;
          }

          if (maintainer.get(NpmConstants.NAME) instanceof final String name
              && name.length() > MAX_MAINTAINER_NAME_LENGTH) {
            return true; // the name column is NOT NULL, so the whole entry is dropped instead.
          }

          final var map = (Map<String, Object>) maintainer;
          dropStringField(map, NpmConstants.EMAIL, MAX_MAINTAINER_EMAIL_LENGTH);
          dropStringField(map, NpmConstants.URL, MAX_MAINTAINER_URL_LENGTH);

          return false;
        });
  }

  private static void dropStringField(
      final Map<String, Object> map, final String field, final int maxLength) {

    if (map.get(field) instanceof final String value && value.length() > maxLength) {
      map.put(field, null);
    }
  }
}
