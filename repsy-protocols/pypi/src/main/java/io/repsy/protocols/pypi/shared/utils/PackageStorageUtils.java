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
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.web.multipart.MultipartFile;

@UtilityClass
@NullMarked
public final class PackageStorageUtils {
  public static final String HASH_ALGORITHM = "sha256";

  private static final Pattern ARCHIVE_FILENAME_PATTERN =
      Pattern.compile(
          "^(?:[a-zA-Z0-9]|[a-zA-Z0-9]"
              + "[a-zA-Z0-9._-]*[a-zA-Z0-9])-(?<version>(?:[1-9][0-9]*!)?(?:0|[1-9][0-9]*)(?:\\.(?:0|[1-9][0-9]*))*"
              + "(?:(?:a|b|rc)(?:0|[1-9][0-9]*))?(?:\\.post(?:0|[1-9][0-9]*))?(?:\\.dev(?:0|[1-9][0-9]*))?)");

  @Nullable
  public static String extractVersionFromArchiveFilename(final String filename) {

    final var matcher = ARCHIVE_FILENAME_PATTERN.matcher(filename);

    if (matcher.find()) {
      return io.repsy.protocols.pypi.shared.utils.ReleaseVersion.of(matcher.group("version"))
          .getVersion();
    }

    return null;
  }

  public static void checkArchiveFilename(final MultipartFile file) {

    final var originalFilename = file.getOriginalFilename();

    if (originalFilename == null) {
      throw new BadRequestException("archiveFileNameNull");
    }

    final var matcher = ARCHIVE_FILENAME_PATTERN.matcher(originalFilename);

    if (!matcher.find()) {
      throw new BadRequestException("archiveFileNameInvalid");
    }
  }

  public static boolean isFileBelongsRelease(final String filename, final String version) {
    final String extractedVersion = PackageStorageUtils.extractVersionFromArchiveFilename(filename);

    if (extractedVersion == null) {
      return false;
    }

    return ReleaseVersion.of(extractedVersion).getVersion().equals(version);
  }
}
