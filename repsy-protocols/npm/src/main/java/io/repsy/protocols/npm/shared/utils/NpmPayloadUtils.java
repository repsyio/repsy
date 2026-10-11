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
package io.repsy.protocols.npm.shared.utils;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.protocols.npm.shared.constants.NpmConstants;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.experimental.UtilityClass;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.digest.DigestUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.data.util.Pair;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@SuppressWarnings("unchecked")
@UtilityClass
/** What a publish, unpublish or deprecate payload says: its parts, lengths and identity. */
public final class NpmPayloadUtils {
  /**
   * The one version the client removed from the packument it sends back on {@code npm unpublish}:
   * the version stored but absent from the payload.
   *
   * <p>Exactly one is required. A payload that lacks none removes nothing, and one that lacks more
   * than one is stale: a version was published after the client read the metadata, so it is absent
   * from the payload too, and picking one of them could delete a version nobody asked to remove
   * (RPS-1289). The payload cannot be reconciled by a lock, so the client is told to read it again.
   *
   * @throws ItemAlreadyExistException With the fixed {@code unpublishPayloadStale} id (409).
   * @throws BadRequestException When the payload has no {@code versions} object.
   */
  public static String findUnpublishedVersion(
      final Map<String, Object> oldMetadata, final Map<String, Object> newMetadata) {

    final var oldVersions =
        Objects.requireNonNull((Map<String, Object>) oldMetadata.get(NpmConstants.VERSIONS));

    if (!(newMetadata.get(NpmConstants.VERSIONS) instanceof final Map<?, ?> newVersions)) {
      throw new BadRequestException(ProtocolErrorCodes.BAD_REQUEST);
    }

    final var missing =
        oldVersions.keySet().stream().filter(name -> !newVersions.containsKey(name)).toList();

    if (missing.size() != 1) {
      throw new ItemAlreadyExistException(ProtocolErrorCodes.UNPUBLISH_PAYLOAD_STALE);
    }

    return missing.getFirst();
  }

  public static List<Pair<String, String>> findDeprecatedVersions(
      final Map<String, Object> oldMetadata, final Map<String, Object> newMetadata) {

    final var deprecatedVersions = new ArrayList<Pair<String, String>>();

    final var newVersions =
        Objects.requireNonNull((Map<String, Object>) newMetadata.get(NpmConstants.VERSIONS));
    final var oldVersions =
        Objects.requireNonNull((Map<String, Object>) oldMetadata.get(NpmConstants.VERSIONS));

    for (final var entry : oldVersions.entrySet()) {
      final var newVersion = (Map<String, Object>) newVersions.get(entry.getKey());

      // A version the payload lacks was published after the client read the metadata: it is not
      // something the client deprecated.
      if (newVersion != null && newVersion.containsKey(NpmConstants.DEPRECATED)) {
        final var entryVersion = (Map<String, Object>) entry.getValue();
        final var entryDeprecated = (String) entryVersion.getOrDefault(NpmConstants.DEPRECATED, "");
        final var newDeprecated = (String) newVersion.getOrDefault(NpmConstants.DEPRECATED, "");

        if (!entryDeprecated.equals(newDeprecated)) {
          deprecatedVersions.add(Pair.of(entry.getKey(), newDeprecated));
        }
      }
    }

    return deprecatedVersions;
  }

  public static boolean isMetadataHasDeprecatedVersions(final Map<String, Object> metadata) {
    final Map<String, Object> versions =
        Objects.requireNonNull((Map<String, Object>) metadata.get(NpmConstants.VERSIONS));
    Map<String, Object> version;

    for (final Map.Entry<String, Object> versionEntry : versions.entrySet()) {
      version = (Map<String, Object>) versionEntry.getValue();

      if (version.get(NpmConstants.DEPRECATED) != null) {
        return true;
      }
    }

    return false;
  }

  public static long getTarballLength(final Map<String, Object> payload) {

    final var attachments =
        Objects.requireNonNull(
            (Map<String, Map<String, Object>>) payload.get(NpmConstants.ATTACHMENTS));
    final var attachment = attachments.entrySet().iterator().next().getValue();

    var length = (Integer) attachment.get("length");

    if (length == null) {
      final var binaryData =
          Base64.decodeBase64(NpmPayloadUtils.extractTarballDataFromPayload(payload));
      length = binaryData.length;
    }

    return length;
  }

  public static long getMetadataLength(final Map<String, Object> metadata) throws JacksonException {

    final var attachments =
        (Map<String, Map<String, Object>>) metadata.remove(NpmConstants.ATTACHMENTS);

    final var mapper = new ObjectMapper();
    final var length = mapper.writeValueAsBytes(metadata).length;

    if (attachments != null) {
      metadata.put(NpmConstants.ATTACHMENTS, attachments);
    }

    return length;
  }

  public static Map.Entry<String, String> extractFirstDistTagFromPayload(
      final Map<String, Object> payload) throws ClassCastException {

    final var distTags =
        Objects.requireNonNull((Map<String, String>) payload.get(NpmConstants.DIST_TAGS));

    return distTags.entrySet().iterator().next();
  }

  public static String extractTarballDataFromPayload(final Map<String, Object> payload)
      throws ClassCastException {

    final var attachments =
        Objects.requireNonNull((Map<String, Map<String, String>>) payload.get("_attachments"));
    final var attachment = attachments.entrySet().iterator().next().getValue();

    return attachment.getOrDefault("data", "");
  }

  public static Pair<String, Map<String, Object>> extractVersionFromPayload(
      final Map<String, Object> payload) throws ClassCastException {

    final var versions =
        Objects.requireNonNull((Map<String, Object>) payload.get(NpmConstants.VERSIONS));
    final var versionEntry = versions.entrySet().iterator().next();
    final var version = (Map<String, Object>) versionEntry.getValue();

    return Pair.of(versionEntry.getKey(), version);
  }

  private static Pair<String, Map<String, Object>> extractVersionFromPayload(
      final Map<String, Object> payload, final String versionName) throws ClassCastException {

    final var versions =
        Objects.requireNonNull((Map<String, Object>) payload.get(NpmConstants.VERSIONS));
    final var version = Objects.requireNonNull((Map<String, Object>) versions.get(versionName));

    return Pair.of(versionName, version);
  }

  public static String extractVersionNameFromPayload(final Map<String, Object> payload)
      throws ClassCastException {

    final var version =
        Objects.requireNonNull((Map<String, Object>) payload.get(NpmConstants.VERSIONS))
            .entrySet()
            .iterator()
            .next();
    final var versionName = version.getKey();

    NpmSemver.parse(
        versionName); // throws BadRequestException(ProtocolErrorCodes.INVALID_PACKAGE_VERSION)

    return versionName;
  }

  /**
   * Refuses a publish or deprecate body whose declared identity is not the package the URL was PUT
   * to. {@code AbstractNpmProtocolFacade.publish} used to register and store a package entirely
   * from the URL's {@code scopeName}/{@code packageName}; the body's own {@code name}, {@code _id}
   * and each {@code versions[*].name} were never compared against it, so a client could publish
   * content under one name whose served metadata claimed a different one (RPS-1207, the same class
   * of gap RPS-1193 closed for a Maven POM's groupId).
   *
   * <p>Only fields that are present and are a {@code String} are compared; a payload missing a
   * field entirely is not refused for it.
   *
   * @throws BadRequestException With the fixed {@code packageNameMismatch} id.
   */
  public static void checkPackageNameMatchesUrl(
      final Map<String, Object> payload,
      final @Nullable String scopeName,
      final String packageName) {

    final var expected = NpmPackageUtils.buildFullName(scopeName, packageName);

    checkNameMatches(expected, payload.get(NpmConstants.NAME));
    checkNameMatches(expected, payload.get(NpmConstants.ID));

    if (payload.get(NpmConstants.VERSIONS) instanceof final Map<?, ?> versions) {
      for (final var entry : versions.values()) {
        if (entry instanceof final Map<?, ?> version) {
          checkNameMatches(expected, version.get(NpmConstants.NAME));
        }
      }
    }
  }

  private static void checkNameMatches(final String expected, final @Nullable Object actual) {

    if (actual instanceof final String name && !expected.equals(name)) {
      throw new BadRequestException(ProtocolErrorCodes.PACKAGE_NAME_MISMATCH);
    }
  }

  /**
   * With every new latest version published some fields of the version object must be lifted up to
   * the top level <a href=
   * "https://github.com/npm/registry/blob/master/docs/responses/package-metadata.md#full-metadata-format">...</a>
   */
  public static void liftFieldsToTopLevel(
      final Map<String, Object> payload, final String versionName) {

    final var versionPair = NpmPayloadUtils.extractVersionFromPayload(payload, versionName);

    final var version = versionPair.getSecond();

    if (version.get(NpmConstants.AUTHOR) != null) {
      payload.put(NpmConstants.AUTHOR, version.get(NpmConstants.AUTHOR));
    }

    if (version.get(NpmConstants.BUGS) != null) {
      payload.put(NpmConstants.BUGS, version.get(NpmConstants.BUGS));
    }

    if (version.get(NpmConstants.CONTRIBUTORS) != null) {
      payload.put(NpmConstants.CONTRIBUTORS, version.get(NpmConstants.CONTRIBUTORS));
    }

    if (version.get(NpmConstants.MAINTAINERS) != null) {
      payload.put(NpmConstants.MAINTAINERS, version.get(NpmConstants.MAINTAINERS));
    }

    if (version.get(NpmConstants.REPOSITORY) != null) {
      payload.put(NpmConstants.REPOSITORY, version.get(NpmConstants.REPOSITORY));
    }

    payload.put("description", version.getOrDefault("description", ""));
    payload.put("homepage", version.getOrDefault("homepage", ""));
    payload.put("keywords", version.getOrDefault("keywords", new ArrayList<String>()));
    payload.put("license", version.getOrDefault("license", ""));
    payload.put("readme", version.getOrDefault("readme", ""));
    payload.put("readmeFilename", version.getOrDefault("readmeFilename", ""));
  }

  public static void updateDistFields(
      final Map<String, Object> metadata, final String versionName, final byte[] tarballBytes) {

    final var shasum = DigestUtils.sha1Hex(tarballBytes);
    final var integrity =
        "sha512-" + Base64.encodeBase64String(DigestUtils.getSha512Digest().digest(tarballBytes));

    final var versions =
        Objects.requireNonNull((Map<String, Object>) metadata.get(NpmConstants.VERSIONS));
    final var version = Objects.requireNonNull((Map<String, Object>) versions.get(versionName));

    final var dist = Objects.requireNonNull((Map<String, Object>) version.get("dist"));

    dist.put("shasum", shasum);
    dist.put("integrity", integrity);
  }
}
