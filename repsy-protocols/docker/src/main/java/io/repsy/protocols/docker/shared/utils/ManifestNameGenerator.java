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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Names the manifest files of a repo. A manifest is stored once per digest, at {@code
 * manifests/<sha256 digest>} (like {@code blobs/<digest>}). Versions before RPS-1216 generated the
 * name from the image and the tag the manifest was pushed under; {@link #generate} is what is left
 * of that scheme, for the files that have not been renamed yet ({@code
 * docker_manifest.storage_name}).
 */
@UtilityClass
@NullMarked
public final class ManifestNameGenerator {

  private static final int SHORT_HASH_LENGTH = 12;
  private static final int UUID_LENGTH = 8;

  /**
   * The name of the file a manifest is stored under, inside the repo's {@code manifests} directory.
   *
   * @param storageName The manifest's {@code storage_name}: the reference a legacy file name was
   *     generated from, or {@code null} once the file lives at its digest
   */
  public static String fileName(
      final UUID repoUuid,
      final String imageName,
      final String digest,
      final @Nullable String storageName) {

    return storageName == null ? digest : generate(repoUuid, imageName, storageName);
  }

  public static String generate(
      final UUID repoUuid, final String imageName, final String reference) {

    try {
      final var uniqueString = String.format("%s:%s:%s", repoUuid, imageName, reference);

      final var digest = MessageDigest.getInstance("SHA-256");
      final var digestHash = digest.digest(uniqueString.getBytes(StandardCharsets.UTF_8));

      final var shortHash = HexFormat.of().formatHex(digestHash).substring(0, SHORT_HASH_LENGTH);

      return String.format(
          "manifest_%s_%s_%s", shortHash, sanitizeFileName(imageName), sanitizeFileName(reference));

    } catch (final NoSuchAlgorithmException e) {
      return String.format(
          "manifest_%s_%s_%s",
          repoUuid.toString().substring(0, UUID_LENGTH),
          sanitizeFileName(imageName),
          sanitizeFileName(reference));
    }
  }

  private static String sanitizeFileName(final String input) {

    return input.replaceAll("[^a-zA-Z0-9._-]", "_").toLowerCase(Locale.getDefault());
  }
}
