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

import static io.repsy.protocols.docker.shared.utils.MediaTypes.DOCKER_MANIFEST_LIST;
import static io.repsy.protocols.docker.shared.utils.MediaTypes.DOCKER_MANIFEST_SCHEMA1;
import static io.repsy.protocols.docker.shared.utils.MediaTypes.DOCKER_MANIFEST_SCHEMA2;
import static io.repsy.protocols.docker.shared.utils.MediaTypes.OCI_IMAGE_INDEX;
import static io.repsy.protocols.docker.shared.utils.MediaTypes.OCI_MANIFEST_SCHEMA1;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.protocols.docker.shared.tag.dtos.Config;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestInfo;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestLayer;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestList;
import java.util.List;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks a pushed manifest before anything is stored for it.
 *
 * <p>The push reads the manifest's config and layers (or an index's manifests) unconditionally, so
 * a body that is not JSON, or one that lacks them, used to fail deep inside the push with a {@code
 * 500}. Each way the manifest can be malformed is instead the client's mistake, answered with a
 * {@code 400} whose message id says what is wrong.
 */
@UtilityClass
@NullMarked
public final class DockerManifestValidator {

  /** Reads a manifest the way the application's mapper does: fields the registry ignores pass. */
  private static final ObjectMapper OBJECT_MAPPER =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  /** The only {@code schemaVersion} the legacy Docker schema1 manifest media type allows. */
  private static final int SCHEMA_VERSION_1 = 1;

  /** The {@code schemaVersion} every other manifest, and every index, media type requires. */
  private static final int SCHEMA_VERSION_2 = 2;

  /**
   * Refuses a manifest the registry cannot store.
   *
   * @param contentType The {@code Content-Type} the manifest was pushed with
   * @param manifestJson The pushed body
   * @throws BadRequestException When the body is not a JSON object, has a field of the wrong type,
   *     lacks what its media type requires, or the {@code Content-Type} is not one the registry
   *     stores
   */
  public static void validate(final String contentType, final String manifestJson) {

    switch (contentType) {
      case OCI_MANIFEST_SCHEMA1, DOCKER_MANIFEST_SCHEMA1, DOCKER_MANIFEST_SCHEMA2 ->
          validateImageManifest(contentType, manifestJson);

      case OCI_IMAGE_INDEX, DOCKER_MANIFEST_LIST -> validateIndex(manifestJson);

      default -> throw new BadRequestException("manifestMediaTypeUnsupported");
    }
  }

  private static void validateImageManifest(final String contentType, final String manifestJson) {

    final var manifest = bind(manifestJson, ManifestInfo.class);

    validateConfig(manifest.getConfig());
    validateLayers(manifest.getLayers());

    final var expectedSchemaVersion =
        DOCKER_MANIFEST_SCHEMA1.equals(contentType) ? SCHEMA_VERSION_1 : SCHEMA_VERSION_2;
    validateSchemaVersion(manifest.getSchemaVersion(), expectedSchemaVersion);
  }

  private static void validateConfig(final @Nullable Config config) {

    if (config == null || StringUtils.isBlank(config.getDigest())) {
      throw new BadRequestException("manifestConfigMissing");
    }
  }

  private static void validateLayers(final @Nullable List<ManifestLayer> layers) {

    if (layers == null
        || layers.stream()
            .anyMatch(layer -> layer == null || StringUtils.isBlank(layer.getDigest()))) {
      throw new BadRequestException("manifestLayersInvalid");
    }
  }

  private static void validateIndex(final String manifestJson) {

    final var index = bind(manifestJson, ManifestList.class);

    final var manifests = index.getManifests();
    if (manifests == null
        || manifests.stream()
            .anyMatch(entry -> entry == null || StringUtils.isBlank(entry.getDigest()))) {
      throw new BadRequestException("manifestListManifestsInvalid");
    }

    validateSchemaVersion(index.getSchemaVersion(), SCHEMA_VERSION_2);
  }

  /**
   * A {@code schemaVersion} outside what the media type allows is either truncated silently (a
   * {@code long} narrowed to the database's {@code int} column, RPS-1151) or plainly wrong, so it
   * is refused up front instead of ever reaching the cast.
   */
  private static void validateSchemaVersion(final long actual, final int expected) {

    if (actual != expected) {
      throw new BadRequestException("manifestSchemaVersionInvalid");
    }
  }

  /** Parses the body as a JSON object and binds it to the type the push reads it as. */
  private static <T> T bind(final String manifestJson, final Class<T> type) {

    final JsonNode tree;
    try {
      tree = OBJECT_MAPPER.readTree(manifestJson);
    } catch (final JacksonException _) {
      throw new BadRequestException("manifestInvalidJson");
    }

    if (!tree.isObject()) {
      throw new BadRequestException("manifestInvalidJson");
    }

    try {
      return OBJECT_MAPPER.treeToValue(tree, type);
    } catch (final JacksonException _) {
      throw new BadRequestException("manifestInvalid");
    }
  }
}
