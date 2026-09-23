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
package io.repsy.protocols.docker.shared.utils;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.protocols.shared.utils.BlobDigests;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Checks the identifiers a manifest push carries in its URL path, headers and body before anything
 * is looked up or written for it (RPS-1139): the image name, the reference (a tag or a digest), and
 * every media type and platform string a push can supply. Each is stored in a {@code varchar(255)}
 * column (see {@link DockerConstants}), so an over-long or grammatically invalid value used to
 * reach the insert and fail with a generic {@code SQLSTATE 22001} 400. A pull of an already-stored,
 * possibly non-conforming value is never checked by this class: only a push must conform.
 */
@UtilityClass
@NullMarked
public final class DockerPushGuards {

  /**
   * The OCI distribution specification's tag grammar
   * (github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-manifests): a tag never
   * starts with {@code .} or {@code -}, and is at most 128 characters, well inside {@link
   * DockerConstants#MAX_REFERENCE_LENGTH}.
   */
  private static final Pattern TAG_PATTERN = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9._-]{0,127}$");

  /** The single-path-component grammar this registry has always parsed an image name with. */
  private static final Pattern IMAGE_NAME_PATTERN =
      Pattern.compile(DockerConstants.IMAGE_NAME_REGEX);

  /**
   * Refuses an image name that is too long for {@code docker_image.name}, or that does not match
   * the grammar this registry stores image names under.
   *
   * @param name The image name from the push path
   * @throws BadRequestException When the name is invalid
   */
  public static void rejectInvalidImageName(final String name) {

    if (name.length() > DockerConstants.MAX_IMAGE_NAME_LENGTH
        || !IMAGE_NAME_PATTERN.matcher(name).matches()) {
      throw new BadRequestException("dockerImageNameInvalid");
    }
  }

  /**
   * Refuses a reference that is too long for {@code docker_tag.name} / {@code
   * docker_manifest.name}, or that is neither a well-formed tag nor a digest of an algorithm this
   * registry supports. A reference containing {@code :} is always treated as a digest (a tag can
   * never contain one), so it is checked against {@link BlobDigests#isSupported}, the registry's
   * own source of truth for which digest algorithms it accepts, instead of a locally duplicated
   * pattern.
   *
   * @param reference The tag or digest from the push path
   * @throws BadRequestException When the reference is invalid
   */
  public static void rejectInvalidReference(final String reference) {

    final var isDigestShaped = reference.indexOf(':') >= 0;

    if (reference.length() > DockerConstants.MAX_REFERENCE_LENGTH) {
      throw new BadRequestException(
          isDigestShaped ? "dockerDigestInvalid" : "dockerReferenceInvalid");
    }

    if (isDigestShaped) {
      if (!BlobDigests.isSupported(reference)) {
        throw new BadRequestException("dockerDigestInvalid");
      }
      return;
    }

    if (!TAG_PATTERN.matcher(reference).matches()) {
      throw new BadRequestException("dockerReferenceInvalid");
    }
  }

  /**
   * Refuses a media type longer than {@link DockerConstants#MAX_MEDIA_TYPE_LENGTH}: the manifest's
   * {@code Content-Type}, an index's own {@code mediaType}, its config's {@code mediaType}, or a
   * child manifest's {@code mediaType}. A blank value is left for the caller's own presence check,
   * so only the length is judged here.
   *
   * @param mediaType The media type to check, possibly absent
   * @throws BadRequestException When the media type is present and too long
   */
  public static void rejectMediaTypeTooLong(final @Nullable String mediaType) {

    if (mediaType != null && mediaType.length() > DockerConstants.MAX_MEDIA_TYPE_LENGTH) {
      throw new BadRequestException("dockerMediaTypeTooLong");
    }
  }

  /**
   * Refuses a platform string longer than {@link DockerConstants#MAX_PLATFORM_LENGTH}: the {@code
   * os/architecture[/variant]} derived from an image config blob, or from an index entry's {@code
   * platform} object.
   *
   * @param platform The platform string to check
   * @throws BadRequestException When the platform string is too long
   */
  public static void rejectPlatformTooLong(final String platform) {

    if (platform.length() > DockerConstants.MAX_PLATFORM_LENGTH) {
      throw new BadRequestException("dockerPlatformTooLong");
    }
  }
}
