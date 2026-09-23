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
package io.repsy.protocols.maven.shared.utils;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.maven.index.artifact.Gav;
import org.apache.maven.model.Developer;
import org.apache.maven.model.License;
import org.apache.maven.model.Model;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Guards the length of every value the Maven upload path copies from the request path and from the
 * POM into a length-limited column, before it is written (RPS-1138). {@code ArtifactServiceImpl}
 * used to copy them unguarded into about twenty columns of {@code maven_artifact}, {@code
 * maven_artifact_version}, {@code maven_version_license} and {@code maven_version_developer}; an
 * over-long one failed the row insert with SQLSTATE 22001, {@code ErrorHandler} (RPS-1012) turned
 * that into a generic 400 that named no field, and the file was already in storage.
 *
 * <p>Three policies apply, matching {@code NpmPublishLimits}, {@code PypiPublishLimits} and {@code
 * DockerPushGuards}:
 *
 * <ul>
 *   <li><b>Reject</b> the upload with a 400 naming the field, before anything is stored: the
 *       groupId, the artifactId and the version of the path, and the packaging of a POM. They are
 *       the coordinates, and the packaging selects how the artifact is served, so cutting or
 *       dropping one would register the file under the wrong identity.
 *   <li><b>Drop</b> the value (set it to {@code null}) and keep the rest of the upload: name, url,
 *       organization, SCM url, plugin prefix, a license's url and a developer's email. They are
 *       descriptive only, and a value cut to fit would point somewhere wrong. The parent
 *       coordinates go together: a group without its version would name a different parent.
 *   <li><b>Drop the entry</b> and keep the rest of the list: a license or developer whose name is
 *       over-long or missing. {@code maven_version_license.name} and {@code
 *       maven_version_developer.name} are {@code NOT NULL}, so unlike their url and email they
 *       cannot be nulled.
 * </ul>
 *
 * <p>The version is capped at {@link #MAX_VERSION_LENGTH} (255), below {@code
 * maven_artifact_version.version_name} ({@code varchar(500)}), because the highest and the highest
 * release version are also copied into {@code maven_artifact.latest} and {@code release}, which are
 * {@code varchar(255)}: a longer version could be inserted and then fail that update.
 */
@NullMarked
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MavenPublishLimits {

  // Reject: the coordinates (maven_artifact.group_name / artifact_name, the version name, and the
  // artifact's latest / release) and the packaging.
  public static final int MAX_GROUP_ID_LENGTH = 255;
  public static final int MAX_ARTIFACT_ID_LENGTH = 255;
  public static final int MAX_VERSION_LENGTH = 255;
  public static final int MAX_PACKAGING_LENGTH = 50;

  // Drop (null): the descriptive fields of maven_artifact and maven_artifact_version.
  public static final int MAX_NAME_LENGTH = 255;
  public static final int MAX_URL_LENGTH = 255;
  public static final int MAX_ORGANIZATION_LENGTH = 150;
  public static final int MAX_PREFIX_LENGTH = 150;
  public static final int MAX_PARENT_COORDINATE_LENGTH = 255;

  // Drop (null) or drop the whole entry: maven_version_license and maven_version_developer.
  public static final int MAX_LICENSE_NAME_LENGTH = 255;
  public static final int MAX_LICENSE_URL_LENGTH = 255;
  public static final int MAX_DEVELOPER_NAME_LENGTH = 255;
  public static final int MAX_DEVELOPER_EMAIL_LENGTH = 255;

  /**
   * Refuses a path whose groupId, artifactId or version does not fit the columns it is registered
   * in. A snapshot is registered by its base version, so that is the one measured.
   *
   * @throws BadRequestException With {@code groupIdTooLong}, {@code artifactIdTooLong} or {@code
   *     mavenVersionTooLong}.
   */
  public static void checkCoordinates(final Gav gav) {

    if (gav.getGroupId().length() > MAX_GROUP_ID_LENGTH) {
      throw new BadRequestException("groupIdTooLong");
    }
    if (gav.getArtifactId().length() > MAX_ARTIFACT_ID_LENGTH) {
      throw new BadRequestException("artifactIdTooLong");
    }

    final var version = gav.isSnapshot() ? gav.getBaseVersion() : gav.getVersion();

    if (version.length() > MAX_VERSION_LENGTH) {
      throw new BadRequestException("mavenVersionTooLong");
    }
  }

  /**
   * Refuses a POM whose packaging is longer than {@link #MAX_PACKAGING_LENGTH}, matching {@code
   * maven_artifact.packaging} and {@code maven_artifact_version.packaging}.
   *
   * @throws BadRequestException With {@code pomPackagingTooLong}.
   */
  public static void checkPackaging(final Model model) {

    final var packaging = model.getPackaging();

    if (packaging != null && packaging.length() > MAX_PACKAGING_LENGTH) {
      throw new BadRequestException("pomPackagingTooLong");
    }
  }

  /**
   * Drops every over-long descriptive value from a parsed POM, in place, so what {@code
   * ArtifactServiceImpl} copies into its rows already fits their columns. It must run after the
   * checks that read the parent (the groupId of a POM without its own is its parent's).
   */
  public static void dropOverLongFields(final Model model) {

    model.setName(dropIfTooLong(model.getName(), MAX_NAME_LENGTH));
    model.setUrl(dropIfTooLong(model.getUrl(), MAX_URL_LENGTH));
    model.setPackaging(dropIfTooLong(model.getPackaging(), MAX_PACKAGING_LENGTH));

    dropOverLongOrganization(model);
    dropOverLongScm(model);
    dropOverLongParent(model);
    dropOverLongLicenses(model);
    dropOverLongDevelopers(model);
  }

  /** {@code value}, or {@code null} when it is longer than {@code maxLength}. */
  public static @Nullable String dropIfTooLong(final @Nullable String value, final int maxLength) {

    return value != null && value.length() > maxLength ? null : value;
  }

  private static void dropOverLongOrganization(final Model model) {

    final var organization = model.getOrganization();

    if (organization != null) {
      organization.setName(dropIfTooLong(organization.getName(), MAX_ORGANIZATION_LENGTH));
    }
  }

  private static void dropOverLongScm(final Model model) {

    final var scm = model.getScm();

    if (scm != null) {
      scm.setUrl(dropIfTooLong(scm.getUrl(), MAX_URL_LENGTH));
    }
  }

  private static void dropOverLongParent(final Model model) {

    final var parent = model.getParent();

    if (parent != null
        && (isTooLong(parent.getGroupId(), MAX_PARENT_COORDINATE_LENGTH)
            || isTooLong(parent.getArtifactId(), MAX_PARENT_COORDINATE_LENGTH)
            || isTooLong(parent.getVersion(), MAX_PARENT_COORDINATE_LENGTH))) {
      model.setParent(null);
    }
  }

  private static void dropOverLongLicenses(final Model model) {

    model
        .getLicenses()
        .removeIf(license -> !hasFittingName(license.getName(), MAX_LICENSE_NAME_LENGTH));
    model.getLicenses().forEach(MavenPublishLimits::dropOverLongLicenseUrl);
  }

  private static void dropOverLongLicenseUrl(final License license) {
    license.setUrl(dropIfTooLong(license.getUrl(), MAX_LICENSE_URL_LENGTH));
  }

  private static void dropOverLongDevelopers(final Model model) {

    model
        .getDevelopers()
        .removeIf(developer -> !hasFittingName(developer.getName(), MAX_DEVELOPER_NAME_LENGTH));
    model.getDevelopers().forEach(MavenPublishLimits::dropOverLongDeveloperEmail);
  }

  private static void dropOverLongDeveloperEmail(final Developer developer) {
    developer.setEmail(dropIfTooLong(developer.getEmail(), MAX_DEVELOPER_EMAIL_LENGTH));
  }

  /** A {@code NOT NULL} name is usable when it is present and fits. */
  private static boolean hasFittingName(final @Nullable String name, final int maxLength) {
    return name != null && name.length() <= maxLength;
  }

  private static boolean isTooLong(final @Nullable String value, final int maxLength) {
    return value != null && value.length() > maxLength;
  }
}
