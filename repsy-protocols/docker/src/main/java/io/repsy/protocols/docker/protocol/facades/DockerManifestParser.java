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
package io.repsy.protocols.docker.protocol.facades;

import static io.repsy.protocols.docker.shared.utils.MediaTypes.DOCKER_CONFIG_JSON;
import static io.repsy.protocols.docker.shared.utils.MediaTypes.OCI_CONFIG_JSON;
import static io.repsy.protocols.docker.shared.utils.MediaTypes.OCI_EMPTY;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestInfo;
import io.repsy.protocols.docker.shared.utils.DockerPushGuards;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Reads the platform a pushed image manifest is stored under out of its config blob. */
@NullMarked
final class DockerManifestParser {

  private DockerManifestParser() {}

  /** An attestation (it has a subject, or an empty config) stores no image, so no platform. */
  static boolean isAttestationManifest(final ManifestInfo manifestInfo) {

    return manifestInfo.getSubject() != null
        || OCI_EMPTY.equals(manifestInfo.getConfig().getMediaType());
  }

  /**
   * Only an <em>image config</em> media type ({@code DOCKER_CONFIG_JSON}/{@code OCI_CONFIG_JSON})
   * is required to carry {@code os}/{@code architecture}: any other config media type is an OCI
   * artifact, legitimately without either (RPS-1116).
   */
  static boolean isImageConfigMediaType(final @Nullable String mediaType) {

    return DOCKER_CONFIG_JSON.equals(mediaType) || OCI_CONFIG_JSON.equals(mediaType);
  }

  /**
   * A config blob that is not JSON, or a JSON object without {@code os} or {@code architecture}, is
   * the client's mistake, not a server failure (RPS-1116): {@code org.json} throws a bare {@code
   * JSONException} for both, which is turned into a 400 that names the problem.
   */
  static String parsePlatform(final String config) {

    final JSONObject json;
    try {
      json = new JSONObject(config);
    } catch (final JSONException _) {
      throw new BadRequestException(ProtocolErrorCodes.MANIFEST_CONFIG_INVALID);
    }

    try {
      final var os = json.getString("os");
      final var architecture = json.getString("architecture");

      if (StringUtils.isBlank(os) || StringUtils.isBlank(architecture)) {
        throw new BadRequestException(ProtocolErrorCodes.MANIFEST_CONFIG_INVALID);
      }

      final var platform = os + "/" + architecture;

      // RPS-1139: os/architecture come from the config blob, not the manifest JSON that
      // DockerManifestValidator already checked, so the length is guarded here, before it reaches
      // docker_manifest.platform.
      DockerPushGuards.rejectPlatformTooLong(platform);

      return platform;
    } catch (final JSONException _) {
      throw new BadRequestException(ProtocolErrorCodes.MANIFEST_CONFIG_INVALID);
    }
  }
}
