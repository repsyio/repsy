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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
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

  /**
   * The versions whose {@code deprecated} the payload changed: a message to deprecate, or an empty
   * string to undeprecate.
   *
   * <p>A version without {@code deprecated}, or with a {@code null} one, is untouched (a {@code
   * null} is not a deprecation, as {@link #isMetadataHasDeprecatedVersions} reads it). A value of
   * any other type is not something the client can mean as a message or as a removal, and answering
   * 200 without applying it would hide that, so it is refused (RPS-2219).
   *
   * @throws BadRequestException With {@code badRequest} for a {@code deprecated} that is neither a
   *     string nor {@code null}.
   */
  public static List<Pair<String, String>> findDeprecatedVersions(
      final Map<String, Object> oldMetadata, final Map<String, Object> newMetadata) {

    final var deprecatedVersions = new ArrayList<Pair<String, String>>();

    final var newVersions =
        Objects.requireNonNull((Map<String, Object>) newMetadata.get(NpmConstants.VERSIONS));
    final var oldVersions =
        Objects.requireNonNull((Map<String, Object>) oldMetadata.get(NpmConstants.VERSIONS));

    checkDeprecatedTypes(newVersions);

    for (final var entry : oldVersions.entrySet()) {
      final var newVersion = (Map<String, Object>) newVersions.get(entry.getKey());

      // A version the payload lacks was published after the client read the metadata: it is not
      // something the client deprecated.
      if (newVersion != null
          && newVersion.get(NpmConstants.DEPRECATED) instanceof final String newDeprecated) {
        final var entryDeprecated =
            ((Map<String, Object>) entry.getValue()).get(NpmConstants.DEPRECATED);

        if (!newDeprecated.equals(entryDeprecated == null ? "" : entryDeprecated)) {
          deprecatedVersions.add(Pair.of(entry.getKey(), newDeprecated));
        }
      }
    }

    return deprecatedVersions;
  }

  private static void checkDeprecatedTypes(final Map<String, Object> versions) {

    for (final var version : versions.values()) {
      final var deprecated = ((Map<String, Object>) version).get(NpmConstants.DEPRECATED);

      if (deprecated != null && !(deprecated instanceof String)) {
        throw new BadRequestException(ProtocolErrorCodes.BAD_REQUEST);
      }
    }
  }

  /**
   * Refuses a publish whose {@code dist-tags} point a tag at a version that will not exist once the
   * publish is stored: one that is neither in the body's {@code versions} nor already stored. npm's
   * registry answers 400 for it, and the tag would otherwise be written into the metadata file with
   * nothing behind it (RPS-2219).
   *
   * @param storedVersions The names of the stored versions. Called at most once, and only when a
   *     tag points outside the body, which is not what {@code npm publish} sends.
   * @throws BadRequestException With {@code badRequest}.
   */
  public static void checkDistTagTargets(
      final Map<String, Object> payload, final Supplier<Collection<String>> storedVersions) {

    final var versions = (Map<String, Object>) payload.get(NpmConstants.VERSIONS);

    if (!(payload.get(NpmConstants.DIST_TAGS) instanceof final Map<?, ?> distTags)) {
      return;
    }

    final var outside =
        distTags.values().stream().filter(target -> !versions.containsKey(target)).toList();

    if (outside.isEmpty()) {
      return;
    }

    final var stored = storedVersions.get();

    if (!stored.containsAll(outside)) {
      throw new BadRequestException(ProtocolErrorCodes.BAD_REQUEST);
    }
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

    // A length that is absent or not a number is measured from the data (RPS-2210).
    if (attachment.get("length") instanceof final Number length) {
      return length.longValue();
    }

    return Base64.decodeBase64(NpmPayloadUtils.extractTarballDataFromPayload(payload)).length;
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
   * <p>Only fields that are present are compared; a payload missing a field entirely is not refused
   * for it, but one whose field is not a string (a number, an object) declares no package name at
   * all, so it is refused like a different one (RPS-2210).
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

    if (actual != null && !expected.equals(actual)) {
      throw new BadRequestException(ProtocolErrorCodes.PACKAGE_NAME_MISMATCH);
    }
  }

  /**
   * Refuses a publish or deprecate body whose {@code versions} is not an object of objects, or
   * whose {@code dist-tags} is not an object of strings. Those are what every later step reads with
   * a cast, so a number or a list there used to end in a ClassCastException or NPE outside any
   * handler, a 500 (RPS-2210).
   *
   * @throws BadRequestException With {@code badRequest}.
   */
  public static void checkPayloadShape(final Map<String, Object> payload) {

    if (!(payload.get(NpmConstants.VERSIONS) instanceof final Map<?, ?> versions)
        || !versions.values().stream().allMatch(Map.class::isInstance)) {
      throw new BadRequestException(ProtocolErrorCodes.BAD_REQUEST);
    }

    final var distTags = payload.get(NpmConstants.DIST_TAGS);

    if (distTags != null
        && (!(distTags instanceof final Map<?, ?> tags)
            || !tags.values().stream().allMatch(String.class::isInstance))) {
      throw new BadRequestException(ProtocolErrorCodes.BAD_REQUEST);
    }
  }

  /**
   * Refuses a publish whose one version, its tarball URL or its attachment is not there or is not
   * of the type the publish reads it as: what {@code npm publish} always sends, and what cannot be
   * stored without (RPS-2210). {@link #checkPayloadShape} has already run.
   *
   * @throws BadRequestException With {@code badRequest}, or {@code invalidPackageVersion} for a
   *     {@code version} that is not a string.
   */
  public static void checkPublishPayload(final Map<String, Object> payload) {

    final var versions = (Map<String, Object>) payload.get(NpmConstants.VERSIONS);

    if (versions.isEmpty() || !hasEntries(payload.get(NpmConstants.DIST_TAGS))) {
      throw new BadRequestException(ProtocolErrorCodes.BAD_REQUEST);
    }

    final var version = (Map<String, Object>) versions.values().iterator().next();

    checkVersionField(version);

    if (!hasTarballUrl(version) || !hasAttachmentData(payload.get(NpmConstants.ATTACHMENTS))) {
      throw new BadRequestException(ProtocolErrorCodes.BAD_REQUEST);
    }
  }

  private static void checkVersionField(final Map<String, Object> version) {
    final var declared = version.get(NpmConstants.VERSION);

    if (declared != null && !(declared instanceof String)) {
      throw new BadRequestException(ProtocolErrorCodes.INVALID_PACKAGE_VERSION);
    }
  }

  private static boolean hasEntries(final @Nullable Object value) {
    return value instanceof final Map<?, ?> map && !map.isEmpty();
  }

  private static boolean hasTarballUrl(final Map<String, Object> version) {
    return version.get(NpmConstants.NAME) instanceof String
        && version.get("dist") instanceof final Map<?, ?> dist
        && dist.get(NpmConstants.TARBALL) instanceof String;
  }

  private static boolean hasAttachmentData(final @Nullable Object attachments) {
    return hasEntries(attachments)
        && ((Map<?, ?>) attachments).values().iterator().next() instanceof final Map<?, ?> first
        && first.get("data") instanceof String;
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
