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
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.utils.BlobDigests;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@UtilityClass
@NullMarked
public final class PackageStorageUtils {
  public static final String HASH_ALGORITHM = "sha256";

  private static final String NAME_PART = "(?:[a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9._-]*[a-zA-Z0-9])";

  /**
   * The normalized PEP 440 version grammar an archive filename carries, with the optional local
   * segment ({@code +cu118}, {@code +local.1}) that {@link ReleaseVersion} keeps (RPS-1662). A
   * local segment is lower-case and dot-separated (PEP 440 normalizes {@code -} and {@code _} to
   * {@code .}), so it can never contain the {@code -} that separates the parts of a wheel name.
   */
  private static final String VERSION_REGEX =
      "(?:[1-9][0-9]*!)?(?:0|[1-9][0-9]*)(?:\\.(?:0|[1-9][0-9]*))*"
          + "(?:(?:a|b|rc)(?:0|[1-9][0-9]*))?(?:\\.post(?:0|[1-9][0-9]*))?(?:\\.dev(?:0|[1-9][0-9]*))?"
          + "(?:\\+[a-z0-9]+(?:\\.[a-z0-9]+)*)?";

  private static final String SIDECAR_SUFFIX = "." + HASH_ALGORITHM;

  private static final String WHEEL_SUFFIX = ".whl";

  private static final Pattern VERSION_PATTERN = Pattern.compile(VERSION_REGEX);

  /** A wheel build tag: it starts with a digit, unlike a python tag, and has no dot. */
  private static final Pattern BUILD_TAG_PATTERN = Pattern.compile("[0-9][a-zA-Z0-9_]*");

  private static final Pattern PART_SEPARATOR = Pattern.compile("-");

  /** A wheel name ends with the python, ABI and platform tags. */
  private static final int WHEEL_TAG_COUNT = 3;

  /** Name, version and build tag: the fewest parts a wheel has once its tags are cut off. */
  private static final int PARTS_WITH_BUILD_TAG = 3;

  private static final Pattern ARCHIVE_UPLOAD_PATTERN =
      Pattern.compile(
          "^" + NAME_PART + "-" + VERSION_REGEX + "(?:-[a-zA-Z0-9._]+)*\\.(?:tar\\.gz|whl|zip)$");

  /**
   * The normalized version in an archive file name, or its {@code .sha256} sidecar's, or {@code
   * null} when the name is not one an upload can have stored.
   *
   * <p>The whole name is parsed, never a prefix of it: {@code pkg-1.0+cu118.tar.gz} is version
   * {@code 1.0+cu118}, not {@code 1.0}, so a release only ever claims its own files (RPS-1662). The
   * parts are told apart by where they sit, because a normalized version has no {@code -}: an
   * sdist's version follows the last hyphen of its stem, a wheel's precedes the optional build tag
   * and the three tags.
   */
  @Nullable
  public static String extractVersionFromArchiveFilename(final String filename) {

    final var name =
        filename.endsWith(SIDECAR_SUFFIX) ? stripSuffix(filename, SIDECAR_SUFFIX) : filename;

    // Never run the version grammar on an over-long name: no upload can have stored one.
    if (name.length() > PypiPublishLimits.MAX_ARCHIVE_FILENAME_LENGTH
        || !ARCHIVE_UPLOAD_PATTERN.matcher(name).matches()) {
      return null;
    }

    final var version = versionPart(name);

    if (!VERSION_PATTERN.matcher(version).matches()) {
      return null;
    }

    return ReleaseVersion.of(version).getVersion();
  }

  private static String versionPart(final String name) {

    final var wheel = name.endsWith(WHEEL_SUFFIX);
    final var stem = stripSuffix(name, wheel ? WHEEL_SUFFIX : extensionOfSdist(name));
    final var parts = PART_SEPARATOR.splitAsStream(stem).toList();

    return wheel ? wheelVersion(parts) : parts.getLast();
  }

  private static String extensionOfSdist(final String name) {
    return name.endsWith(".zip") ? ".zip" : ".tar.gz";
  }

  private static String stripSuffix(final String name, final String suffix) {
    return name.substring(0, name.length() - suffix.length());
  }

  private static String wheelVersion(final List<String> parts) {

    final var withoutTags =
        parts.size() > WHEEL_TAG_COUNT + 1
            ? parts.subList(0, parts.size() - WHEEL_TAG_COUNT)
            : parts;
    final var last = withoutTags.getLast();

    if (withoutTags.size() >= PARTS_WITH_BUILD_TAG
        && BUILD_TAG_PATTERN.matcher(last).matches()
        && VERSION_PATTERN.matcher(withoutTags.get(withoutTags.size() - 2)).matches()) {
      return withoutTags.get(withoutTags.size() - 2);
    }

    return last;
  }

  public static void checkArchiveFilename(final MultipartFile file) {

    final var originalFilename = file.getOriginalFilename();

    if (originalFilename == null) {
      throw new BadRequestException(ProtocolErrorCodes.ARCHIVE_FILE_NAME_NULL);
    }

    PypiPublishLimits.checkArchiveFilename(originalFilename);

    if (!ARCHIVE_UPLOAD_PATTERN.matcher(originalFilename).matches()
        || extractVersionFromArchiveFilename(originalFilename) == null) {
      throw new BadRequestException(ProtocolErrorCodes.ARCHIVE_FILE_NAME_INVALID);
    }
  }

  /**
   * Refuses an upload whose file name and {@code version} form field name different versions
   * (RPS-1662): with local versions, {@code pkg-1.0+cu118.whl} filed under release {@code 1.0}
   * would put a file in a release it does not belong to. Both sides are normalized first, so {@code
   * 1.0.RC1} and {@code 1.0rc1} agree. Call it after {@link #checkArchiveFilename}.
   */
  public static void checkArchiveVersion(final MultipartFile file, final String version) {

    final var filenameVersion =
        extractVersionFromArchiveFilename(Objects.requireNonNull(file.getOriginalFilename()));

    if (!ReleaseVersion.of(version).getVersion().equals(filenameVersion)) {
      throw new BadRequestException(ProtocolErrorCodes.ARCHIVE_VERSION_MISMATCH);
    }
  }

  /**
   * Whether the archive file (or its sidecar) is one of the release's files. The versions are
   * compared whole, so release {@code 1.0} never owns the files of {@code 1.0+cu118} (RPS-1662).
   */
  public static boolean isFileBelongsRelease(final String filename, final String version) {
    final String extractedVersion = PackageStorageUtils.extractVersionFromArchiveFilename(filename);

    if (extractedVersion == null) {
      return false;
    }

    return extractedVersion.equals(version);
  }

  /** Rejects an upload whose {@code sha256_digest} form field is missing or blank. */
  public static void checkSha256Digest(final PackageUploadForm uploadForm) {

    if (!StringUtils.hasText(uploadForm.getSha256_digest())) {
      throw new BadRequestException(ProtocolErrorCodes.SHA256_DIGEST_MISSING);
    }
  }

  /**
   * Computes the SHA-256 of the uploaded file's actual bytes, streaming so a large archive is never
   * fully buffered in memory.
   */
  public static String computeSha256(final MultipartFile file) throws IOException {

    try (var in = file.getInputStream()) {
      return BlobDigests.sha256Hex(in);
    }
  }
}
