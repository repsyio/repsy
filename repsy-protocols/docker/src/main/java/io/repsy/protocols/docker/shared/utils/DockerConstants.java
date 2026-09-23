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

import org.jspecify.annotations.NullMarked;

@NullMarked
public final class DockerConstants {

  private DockerConstants() {}

  public static final String IMAGE_NAME_REGEX = "(?<imageName>[a-zA-Z0-9_\\-]+)";
  public static final String SHA_REGEX = "(?<sha>sha256:[0-9a-fA-F]{64})$";
  public static final String BLOBS = "blobs";
  public static final String SHA256_PREFIX = "sha256:";
  public static final String MANIFESTS = "manifests";
  public static final String BASIC_PREFIX = "Basic ";

  /**
   * The platform recorded for a manifest whose real platform is not known: an OCI artifact's config
   * (RPS-1116) or an index entry pushed without a {@code platform} object (RPS-1117).
   */
  public static final String UNKNOWN_PLATFORM = "unknown";

  // The limits of the varchar(255) columns a pushed manifest's identifiers are stored in
  // (RPS-1139). PostgreSQL and H2 create docker_image, docker_tag, docker_tag_platform and
  // docker_manifest with exactly this length in every column a push can reach. DockerPushGuards
  // and DockerManifestValidator refuse anything longer, or grammatically invalid, before it is
  // looked up or written, and the entities take their @Column lengths from here.

  /** {@code docker_image.name}: the image name in the push path. */
  public static final int MAX_IMAGE_NAME_LENGTH = 255;

  /**
   * {@code docker_tag.name} and {@code docker_manifest.name}: the reference (tag or digest) in the
   * push path.
   */
  public static final int MAX_REFERENCE_LENGTH = 255;

  /**
   * {@code docker_tag.media_type}, {@code docker_manifest.media_type} and {@code
   * docker_manifest.config_media_type}: the manifest's {@code Content-Type}, an index's own {@code
   * mediaType}, its config's {@code mediaType}, and a child manifest's {@code mediaType}.
   */
  public static final int MAX_MEDIA_TYPE_LENGTH = 255;

  /**
   * {@code docker_tag.platform}, {@code docker_tag_platform.platform} and {@code
   * docker_manifest.platform}: the {@code os/architecture[/variant]} derived from an image config
   * blob, or from an index entry's {@code platform} object.
   */
  public static final int MAX_PLATFORM_LENGTH = 255;
}
