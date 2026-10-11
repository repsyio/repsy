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
package io.repsy.protocols.helm.shared.utils;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.protocols.helm.shared.constants.HelmConstants;
import io.repsy.protocols.oci.utils.OciDigestUtils;
import io.repsy.protocols.oci.utils.OciMediaTypeUtils;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.utils.BlobDigests;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The strict checks of a pushed Helm OCI manifest, the ones the Docker registry has always made of
 * its manifests (RPS-2164). They are applied only while {@code repsy.helm.oci.strict-wire} is on,
 * so an operator can fall back to the earlier, lenient push without a new release.
 */
@UtilityClass
public final class HelmOciManifestUtils {

  /** The {@code schemaVersion} an OCI image manifest requires. */
  private static final int SCHEMA_VERSION = 2;

  /**
   * Refuses a manifest the registry does not store: a {@code Content-Type} other than the OCI image
   * manifest, a {@code schemaVersion} other than 2, or a config without a digest.
   *
   * @param contentType the {@code Content-Type} the manifest was pushed with
   * @param manifest the parsed body, already known to be a JSON object
   * @throws BadRequestException when the manifest is refused
   */
  public static void validate(final String contentType, final JsonNode manifest) {

    if (!OciMediaTypeUtils.OCI_MANIFEST_SCHEMA1.equals(contentType)) {
      throw new BadRequestException(ProtocolErrorCodes.MANIFEST_MEDIA_TYPE_UNSUPPORTED);
    }

    validateSchemaVersion(manifest.get("schemaVersion"));
    validateConfig(manifest.get("config"));
  }

  private static void validateSchemaVersion(final @Nullable JsonNode schemaVersion) {

    if (schemaVersion == null
        || !schemaVersion.isIntegralNumber()
        || schemaVersion.asInt(-1) != SCHEMA_VERSION) {
      throw new BadRequestException(ProtocolErrorCodes.MANIFEST_SCHEMA_VERSION_INVALID);
    }
  }

  private static void validateConfig(final @Nullable JsonNode config) {

    if (config == null || !isNonBlankString(config.get("digest"))) {
      throw new BadRequestException(ProtocolErrorCodes.MANIFEST_CONFIG_MISSING);
    }

    final var mediaType = config.get("mediaType");
    if (isNonBlankString(mediaType)
        && mediaType.asString().length() > HelmConstants.MAX_OCI_MEDIA_TYPE_LENGTH) {
      throw new BadRequestException(ProtocolErrorCodes.MANIFEST_MEDIA_TYPE_TOO_LONG);
    }
  }

  private static boolean isNonBlankString(final @Nullable JsonNode node) {

    return node != null && node.isString() && !node.asString().isBlank();
  }

  /**
   * Refuses a manifest pushed by a digest reference that is not its own digest, in the algorithm
   * the reference names. A tag reference passes.
   *
   * @param reference the tag or digest of the push path
   * @param sha256 the manifest's {@code sha256:} digest
   * @param sha512 the manifest's {@code sha512:} digest
   * @throws BadRequestException when the reference is a digest of other bytes
   */
  public static void verifyDigestReference(
      final String reference, final String sha256, final String sha512) {

    if (!BlobDigests.startsWithDigestPrefix(reference)) {
      return;
    }

    final var digest = OciDigestUtils.normalize(reference);
    if (!digest.equals(sha256) && !digest.equals(sha512)) {
      throw new BadRequestException(ProtocolErrorCodes.DIGEST_MISMATCH);
    }
  }
}
