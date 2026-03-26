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

import java.util.List;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.util.CollectionUtils;

@UtilityClass
@NullMarked
public final class MediaTypes {

  // OCI Media Types
  public static final String OCI_IMAGE_INDEX = "application/vnd.oci.image.index.v1+json";
  public static final String OCI_MANIFEST_SCHEMA1 = "application/vnd.oci.image.manifest.v1+json";
  // Docker Media Types
  public static final String DOCKER_CONFIG_JSON = "application/vnd.docker.container.image.v1+json";
  public static final String DOCKER_MANIFEST_SCHEMA1 =
      "application/vnd.docker.distribution.manifest.v1+json";
  public static final String DOCKER_MANIFEST_SCHEMA2 =
      "application/vnd.docker.distribution.manifest.v2+json";
  public static final String DOCKER_MANIFEST_LIST =
      "application/vnd.docker.distribution.manifest.list.v2+json";
  public static final String DOCKER_LAYER = "application/vnd.docker.image.rootfs.diff.tar.gzip";
  private static final String OCI_CONTENT_DESCRIPTOR = "application/vnd.oci.descriptor.v1+json";
  private static final String OCI_CONFIG_JSON = "application/vnd.oci.image.config.v1+json";
  private static final String OCI_LAYER = "application/vnd.oci.image.layer.v1.tar+gzip";
  private static final String OCI_LAYER_ZSTD = "application/vnd.oci.image.layer.v1.tar+zstd";
  private static final String OCI_UNCOMPRESSED_LAYER = "application/vnd.oci.image.layer.v1.tar";
  private static final String DOCKER_MANIFEST_SCHEMA1_SIGNED =
      "application/vnd.docker.distribution.manifest.v1+prettyjws";
  private static final String DOCKER_UNCOMPRESSED_LAYER =
      "application/vnd.docker.image.rootfs.diff.tar";
  private static final String APPLICATION_JSON = "application/json";

  public static String getPreferredMediaType(final @Nullable List<String> acceptHeaders) {

    if (CollectionUtils.isEmpty(acceptHeaders)) {
      return MediaTypes.DOCKER_MANIFEST_SCHEMA2;
    }

    final var distributableType = MediaTypes.findDistributableType(acceptHeaders);
    if (distributableType != null) {
      return distributableType;
    }

    final var manifestType = MediaTypes.findManifestType(acceptHeaders);
    if (manifestType != null) {
      return manifestType;
    }

    final var jsonFallback = MediaTypes.findJsonFallback(acceptHeaders);
    if (jsonFallback != null) {
      return jsonFallback;
    }

    return MediaTypes.DOCKER_MANIFEST_SCHEMA2;
  }

  public static boolean isIndex(final String type) {

    return switch (type) {
      case OCI_IMAGE_INDEX, DOCKER_MANIFEST_LIST -> true;
      default -> false;
    };
  }

  private static @Nullable String findDistributableType(final List<String> acceptHeaders) {

    for (final var accept : acceptHeaders) {
      if (MediaTypes.isDistributable(accept)) {
        return accept;
      }
    }
    return null;
  }

  private static @Nullable String findManifestType(final List<String> acceptHeaders) {

    for (final var accept : acceptHeaders) {
      if (MediaTypes.isManifestType(accept)) {
        return accept;
      }
    }
    return null;
  }

  private static @Nullable String findJsonFallback(final List<String> acceptHeaders) {

    for (final var accept : acceptHeaders) {
      if (APPLICATION_JSON.equals(accept)) {
        return MediaTypes.DOCKER_MANIFEST_SCHEMA2;
      }
    }
    return null;
  }

  private static boolean isManifestType(final String type) {

    return switch (type) {
      case DOCKER_MANIFEST_SCHEMA1,
          DOCKER_MANIFEST_SCHEMA1_SIGNED,
          DOCKER_MANIFEST_SCHEMA2,
          DOCKER_MANIFEST_LIST,
          OCI_MANIFEST_SCHEMA1,
          OCI_IMAGE_INDEX ->
          true;
      default -> false;
    };
  }

  private static boolean isDistributable(final String type) {

    return switch (type) {
      case DOCKER_LAYER,
          DOCKER_UNCOMPRESSED_LAYER,
          OCI_LAYER,
          OCI_LAYER_ZSTD,
          OCI_UNCOMPRESSED_LAYER,
          DOCKER_MANIFEST_SCHEMA1,
          DOCKER_MANIFEST_SCHEMA1_SIGNED,
          DOCKER_MANIFEST_SCHEMA2,
          DOCKER_MANIFEST_LIST,
          OCI_MANIFEST_SCHEMA1,
          OCI_IMAGE_INDEX,
          OCI_CONTENT_DESCRIPTOR,
          DOCKER_CONFIG_JSON,
          OCI_CONFIG_JSON ->
          true;
      default -> false;
    };
  }
}
