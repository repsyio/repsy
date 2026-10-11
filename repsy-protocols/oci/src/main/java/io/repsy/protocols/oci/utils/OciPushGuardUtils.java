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
package io.repsy.protocols.oci.utils;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.utils.BlobDigests;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * Checks of a manifest push that every OCI format shares: what the reference of the request path
 * may be. A pull of an already stored, possibly non-conforming value is never checked here: only a
 * push must conform.
 */
@UtilityClass
public final class OciPushGuardUtils {

  /**
   * The OCI distribution specification's tag grammar
   * (github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-manifests): a tag never
   * starts with {@code .} or {@code -}, and is at most 128 characters.
   */
  private static final Pattern TAG_PATTERN = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9._-]{0,127}$");

  /**
   * Refuses a reference that is too long for its column, or that is neither a well-formed tag nor a
   * digest of an algorithm the registry supports. A reference containing {@code :} is always
   * treated as a digest (a tag can never contain one), so it is checked against {@link
   * BlobDigests#isSupported}, the registry's own source of truth for which digest algorithms it
   * accepts.
   *
   * @param reference the tag or digest from the push path
   * @param maxLength the longest reference the format stores
   * @throws BadRequestException when the reference is invalid
   */
  public static void rejectInvalidReference(final String reference, final int maxLength) {

    final var isDigestShaped = reference.indexOf(':') >= 0;

    if (reference.length() > maxLength) {
      throw new BadRequestException(
          isDigestShaped
              ? ProtocolErrorCodes.DOCKER_DIGEST_INVALID
              : ProtocolErrorCodes.DOCKER_REFERENCE_INVALID);
    }

    if (isDigestShaped) {
      if (!BlobDigests.isSupported(reference)) {
        throw new BadRequestException(ProtocolErrorCodes.DOCKER_DIGEST_INVALID);
      }
      return;
    }

    if (!TAG_PATTERN.matcher(reference).matches()) {
      throw new BadRequestException(ProtocolErrorCodes.DOCKER_REFERENCE_INVALID);
    }
  }
}
