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
import io.repsy.protocols.pypi.shared.python_package.dtos.PackageUploadForm;
import java.util.ArrayList;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Guards the length of every value the PyPI publish path stores in a length-limited column, before
 * anything is written (RPS-1137). {@code AbstractPypiProtocolFacade.uploadPackage} writes the
 * archive to storage first (RPS-1124's ordering) and only then lets {@code PypiPackageServiceImpl}
 * copy unguarded upload-form fields into {@code pypi_package}, {@code pypi_release} and their child
 * tables; an over-long one failed the row insert with SQLSTATE 22001, which {@code ErrorHandler}
 * (RPS-1012) turned into a generic 400 that named no field, and the archive was already left behind
 * in storage.
 *
 * <p>Three policies apply, matching the ones {@code CrateUtils}, {@code GoVersionUtils} and {@code
 * HelmConstants} (RPS-1072), {@code NpmPublishLimits} (RPS-1136) and {@code DockerConstants}
 * (RPS-1139) already use for their own publish paths:
 *
 * <ul>
 *   <li><b>Reject</b> the whole publish with a 400 naming the field: the package name (identifies
 *       the package; also bounds {@code normalized_name}, which normalization can only shorten),
 *       the version (identifies the release) and {@code requires_python} (says where the release
 *       installs). These apply to a new release and to an override alike, since both go through
 *       this same upload path.
 *   <li><b>Drop</b> the value (set it to {@code null}) and keep the rest of the publish: {@code
 *       home_page}, {@code author}, {@code author_email}, {@code license} and {@code
 *       description_content_type}. These are descriptive only; a value cut to fit would point
 *       somewhere wrong, which is worse than a blank field. {@code summary} and {@code description}
 *       need no guard here: {@code pypi_release.summary} / {@code description} are {@code text} in
 *       both PostgreSQL and H2 (unbounded), so neither can overflow the row insert this guard
 *       exists to prevent.
 *   <li><b>Drop the entry</b> and keep the rest of the collection: a classifier whose category or
 *       value (split on the first {@code ::}, as {@code PypiPackageServiceImpl} splits it) does not
 *       fit {@code pypi_release_classifier}'s {@code classifier} / {@code value} columns (both
 *       {@code NOT NULL}, so neither half can be nulled instead), and a Project-URL entry whose
 *       label does not fit {@code pypi_release_project_url.label} ({@code varchar(32)}, {@code NOT
 *       NULL}). A Project-URL's own {@code url} needs no guard: the column is {@code text}
 *       (unbounded) in both databases.
 * </ul>
 *
 * <p>{@code pypi_release.version} is {@code varchar(255)}; {@link #MAX_VERSION_LENGTH} is set to
 * that width rather than a narrower one, unlike {@code NpmPublishLimits.MAX_VERSION_LENGTH} (128).
 * npm capped its own constant below its column to also fit inside {@code
 * vulnerability_scan.artifact_version}, which was {@code varchar(128)} at the time; that column was
 * widened to {@code varchar(512)} by RPS-1140 specifically because it was too narrow for {@code
 * pypi_release.version} (see that migration's comment), so no PyPI-side cap below 255 is needed to
 * keep every accepted version inside the shared scan column.
 */
@NullMarked
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PypiPublishLimits {

  // Reject: these identify the package or the release (pypi_package.name / normalized_name,
  // pypi_release.version, pypi_package.stable_version / latest_version,
  // pypi_release.requires_python).
  public static final int MAX_NAME_LENGTH = 255;
  public static final int MAX_VERSION_LENGTH = 255;
  public static final int MAX_REQUIRES_PYTHON_LENGTH = 255;

  // Drop (null): descriptive fields of pypi_release. summary/description need no constant here --
  // see the class Javadoc.
  public static final int MAX_HOME_PAGE_LENGTH = 255;
  public static final int MAX_AUTHOR_LENGTH = 255;
  public static final int MAX_AUTHOR_EMAIL_LENGTH = 255;
  public static final int MAX_LICENSE_LENGTH = 255;
  public static final int MAX_DESCRIPTION_CONTENT_TYPE_LENGTH = 255;

  // Drop the entry, keep the rest of the collection.
  public static final int MAX_CLASSIFIER_LENGTH = 255;
  public static final int MAX_PROJECT_URL_LABEL_LENGTH = 32;

  /**
   * Refuses a package name longer than {@link #MAX_NAME_LENGTH}, matching {@code
   * pypi_package.name}. {@code PackageUtils.normalizePackageName} only collapses separator runs, so
   * it never grows the name, and this bounds {@code pypi_package.normalized_name} too.
   *
   * @throws BadRequestException With {@code pypiPackageNameTooLong}.
   */
  public static void checkPackageName(final String name) {
    if (name.length() > MAX_NAME_LENGTH) {
      throw new BadRequestException("pypiPackageNameTooLong");
    }
  }

  /**
   * Refuses a version longer than {@link #MAX_VERSION_LENGTH}, matching {@code
   * pypi_release.version} and {@code pypi_package.stable_version} / {@code latest_version}.
   *
   * @throws BadRequestException With {@code pypiVersionTooLong}.
   */
  public static void checkVersion(final @Nullable String version) {
    if (version != null && version.length() > MAX_VERSION_LENGTH) {
      throw new BadRequestException("pypiVersionTooLong");
    }
  }

  /**
   * Refuses a {@code requires_python} longer than {@link #MAX_REQUIRES_PYTHON_LENGTH}, matching
   * {@code pypi_release.requires_python}.
   *
   * @throws BadRequestException With {@code pypiRequiresPythonTooLong}.
   */
  public static void checkRequiresPython(final @Nullable String requiresPython) {
    if (requiresPython != null && requiresPython.length() > MAX_REQUIRES_PYTHON_LENGTH) {
      throw new BadRequestException("pypiRequiresPythonTooLong");
    }
  }

  /**
   * Drops every over-long descriptive value from the upload form, in place, so what reaches {@code
   * PypiPackageServiceImpl} already fits its column. A classifier or Project-URL whose own field
   * cannot be nulled loses only that one entry.
   */
  public static void dropOverLongFields(final PackageUploadForm uploadForm) {
    uploadForm.setHome_page(dropIfTooLong(uploadForm.getHome_page(), MAX_HOME_PAGE_LENGTH));
    uploadForm.setAuthor(dropIfTooLong(uploadForm.getAuthor(), MAX_AUTHOR_LENGTH));
    uploadForm.setAuthor_email(
        dropIfTooLong(uploadForm.getAuthor_email(), MAX_AUTHOR_EMAIL_LENGTH));
    uploadForm.setLicense(dropIfTooLong(uploadForm.getLicense(), MAX_LICENSE_LENGTH));
    uploadForm.setDescription_content_type(
        dropIfTooLong(
            uploadForm.getDescription_content_type(), MAX_DESCRIPTION_CONTENT_TYPE_LENGTH));

    dropOverLongClassifiers(uploadForm);
    dropOverLongProjectUrls(uploadForm);
  }

  private static @Nullable String dropIfTooLong(final @Nullable String value, final int maxLength) {
    return value != null && value.length() > maxLength ? null : value;
  }

  private static void dropOverLongClassifiers(final PackageUploadForm uploadForm) {
    final var classifiers = uploadForm.getClassifiers();

    if (classifiers == null) {
      return;
    }

    final var kept = new ArrayList<String>(classifiers.length);

    for (final var classifier : classifiers) {
      if (fitsAsClassifier(classifier)) {
        kept.add(classifier);
      }
    }

    uploadForm.setClassifiers(kept.toArray(new String[0]));
  }

  private static boolean fitsAsClassifier(final @Nullable String classifier) {
    if (classifier == null || classifier.isBlank()) {
      return true; // blank/null entries are skipped downstream; not a length problem.
    }

    final var split = classifier.split("::", 2);

    if (split.length != 2) {
      return true; // malformed entries (no "::") are skipped downstream too.
    }

    return split[0].trim().length() <= MAX_CLASSIFIER_LENGTH
        && split[1].trim().length() <= MAX_CLASSIFIER_LENGTH;
  }

  private static void dropOverLongProjectUrls(final PackageUploadForm uploadForm) {
    final var projectUrls = uploadForm.getProject_urls();

    if (projectUrls == null) {
      return;
    }

    final var kept = new ArrayList<String>(projectUrls.length);

    for (final var projectUrl : projectUrls) {
      if (fitsAsProjectUrl(projectUrl)) {
        kept.add(projectUrl);
      }
    }

    uploadForm.setProject_urls(kept.toArray(new String[0]));
  }

  private static boolean fitsAsProjectUrl(final @Nullable String projectUrl) {
    if (projectUrl == null || projectUrl.isBlank()) {
      return true; // blank/null entries are skipped downstream; not a length problem.
    }

    final var split = projectUrl.split(",", -1);

    if (split.length != 2) {
      return true; // malformed entries (not exactly one comma) are skipped downstream too.
    }

    return split[0].length() <= MAX_PROJECT_URL_LABEL_LENGTH;
  }
}
