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
package io.repsy.os.server.protocols.docker.shared.utils;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.server.protocols.docker.shared.constants.DockerConstants;
import java.util.regex.Pattern;
import lombok.Builder;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@UtilityClass
public class PathParserUtils {

  private static final @NonNull PathConfig LAYER_UPLOAD =
      PathConfig.builder()
          .basePath(DockerConstants.BLOBS)
          .pattern(
              Pattern.compile(
                  DockerConstants.REPO_NAME_PATTERN
                      + DockerConstants.IMAGE_NAME_PATTERN
                      + "/blobs"
                      + "(/uploads)?"
                      + "/(?<id>[a-zA-Z0-9_\\-]+)"))
          .groupName("id")
          .pathType("layer upload")
          .build();

  private static final @NonNull PathConfig LAYER_CHECK =
      PathConfig.builder()
          .basePath(DockerConstants.BLOBS)
          .pattern(
              Pattern.compile(
                  DockerConstants.REPO_NAME_PATTERN
                      + DockerConstants.IMAGE_NAME_PATTERN
                      + "/blobs"
                      + "/(?<sha>sha256:[0-9a-fA-F]{64})$"))
          .groupName("sha")
          .pathType("layer check")
          .build();

  private static final @NonNull PathConfig MANIFEST_SHA =
      PathConfig.builder()
          .basePath(DockerConstants.MANIFESTS)
          .pattern(
              Pattern.compile(
                  DockerConstants.REPO_NAME_PATTERN
                      + DockerConstants.IMAGE_NAME_PATTERN
                      + "/manifests"
                      + "/(?<sha>sha256:[0-9a-fA-F]{64})$"))
          .groupName("sha")
          .pathType("manifest sha")
          .build();

  private static final @NonNull PathConfig MANIFEST_TAG =
      PathConfig.builder()
          .basePath(DockerConstants.MANIFESTS)
          .pattern(
              Pattern.compile(
                  DockerConstants.REPO_NAME_PATTERN
                      + DockerConstants.IMAGE_NAME_PATTERN
                      + "/manifests"
                      + "/(?<tag>[a-zA-Z0-9_.\\-]+)$"))
          .groupName("tag")
          .pathType("manifest tag")
          .build();

  public static @NonNull ParsedPath parseForLayer(
      @NonNull final String requestPath, @NonNull final String fileName) {

    final var config = requestPath.contains("sha256:") ? LAYER_CHECK : LAYER_UPLOAD;
    return parsePath(requestPath, config, requestPath.contains("sha256:") ? null : fileName);
  }

  public static @NonNull ParsedPath parseForManifest(
      @NonNull final String requestPath, @NonNull final String fileName) {

    final var config = requestPath.contains("sha256:") ? MANIFEST_SHA : MANIFEST_TAG;
    return parsePath(requestPath, config, fileName);
  }

  private static @NonNull String cleanPath(@NonNull final String requestPath) {
    return requestPath.replaceFirst("\\?.*", "").replaceFirst("^/", "").replaceFirst("^v2/", "");
  }

  private static @NonNull ParsedPath parsePath(
      @NonNull final String requestPath,
      @NonNull final PathConfig config,
      @Nullable final String fileName) {

    final var cleanedPath = cleanPath(requestPath);
    final var matcher = config.pattern().matcher(cleanedPath);

    if (!matcher.matches()) {
      throw new BadRequestException(
          String.format("Invalid %s path format: %s", config.pathType(), cleanedPath));
    }

    final var parsedValue = fileName != null ? fileName : matcher.group(config.groupName());

    return ParsedPath.builder()
        .repoName(matcher.group("repoName"))
        .imageName(matcher.group("imageName"))
        .relativePath(new RelativePath(config.basePath() + "/" + parsedValue))
        .build();
  }

  @Builder
  private record PathConfig(
      @NonNull String basePath,
      @NonNull Pattern pattern,
      @NonNull String groupName,
      @NonNull String pathType) {}
}
