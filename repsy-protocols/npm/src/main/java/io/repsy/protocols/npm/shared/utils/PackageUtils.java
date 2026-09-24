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
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.experimental.UtilityClass;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.digest.DigestUtils;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.data.util.Pair;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@SuppressWarnings("unchecked")
@UtilityClass
@NullMarked
public final class PackageUtils {
  private static final String TARBALL_EXTENSION = "tgz";
  private static final String ABBREVIATED_METADATA_HEADER_VALUE =
      "application/vnd.npm.install-v1+json";

  public static void updateModifiedTime(final Map<String, Object> metadata) {
    final var timeField = (Map<String, String>) metadata.get("time");

    if (timeField != null) {
      timeField.put("modified", PackageUtils.getFormattedCurrentTime());
    }
  }

  public static String findUnpublishedVersion(
      final Map<String, Object> oldMetadata, final Map<String, Object> newMetadata) {

    final var oldVersions = (Map<String, Object>) oldMetadata.get(NpmConstants.VERSIONS);
    final var newVersions = (Map<String, Object>) newMetadata.get(NpmConstants.VERSIONS);

    for (final var entry : oldVersions.entrySet()) {
      if (!newVersions.containsKey(entry.getKey())) {
        return entry.getKey();
      }
    }

    return "";
  }

  public static List<Pair<String, String>> findDeprecatedVersions(
      final Map<String, Object> oldMetadata, final Map<String, Object> newMetadata) {

    final var deprecatedVersions = new ArrayList<Pair<String, String>>();

    final var newVersions = (Map<String, Object>) newMetadata.get(NpmConstants.VERSIONS);
    final var oldVersions = (Map<String, Object>) oldMetadata.get(NpmConstants.VERSIONS);

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
    final Map<String, Object> versions = (Map<String, Object>) metadata.get(NpmConstants.VERSIONS);
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
        (Map<String, Map<String, Object>>) payload.get(NpmConstants.ATTACHMENTS);
    final var attachment = attachments.entrySet().iterator().next().getValue();

    var length = (Integer) attachment.get("length");

    if (length == null) {
      final var binaryData =
          Base64.decodeBase64(PackageUtils.extractTarballDataFromPayload(payload));
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

    final var distTags = (Map<String, String>) payload.get(NpmConstants.DIST_TAGS);

    return distTags.entrySet().iterator().next();
  }

  public static String extractTarballDataFromPayload(final Map<String, Object> payload)
      throws ClassCastException {

    final var attachments = (Map<String, Map<String, String>>) payload.get("_attachments");
    final var attachment = attachments.entrySet().iterator().next().getValue();

    return attachment.getOrDefault("data", "");
  }

  public static Pair<String, Map<String, Object>> extractVersionFromPayload(
      final Map<String, Object> payload) throws ClassCastException {

    final var versions = (Map<String, Object>) payload.get(NpmConstants.VERSIONS);
    final var versionEntry = versions.entrySet().iterator().next();
    final var version = (Map<String, Object>) versionEntry.getValue();

    return Pair.of(versionEntry.getKey(), version);
  }

  private static Pair<String, Map<String, Object>> extractVersionFromPayload(
      final Map<String, Object> payload, final String versionName) throws ClassCastException {

    final var versions = (Map<String, Object>) payload.get(NpmConstants.VERSIONS);
    final var version = (Map<String, Object>) versions.get(versionName);

    return Pair.of(versionName, version);
  }

  public static String extractVersionNameFromPayload(final Map<String, Object> payload)
      throws ClassCastException {

    final var version =
        ((Map<String, Object>) payload.get(NpmConstants.VERSIONS)).entrySet().iterator().next();
    final var versionName = version.getKey();

    NpmSemver.parse(versionName); // throws BadRequestException("invalidPackageVersion")

    return versionName;
  }

  /**
   * Normalizes a published version's {@code dist.tarball} without assuming any particular registry
   * URL layout.
   *
   * <p>The client (npm itself) already computes a servable path, so this only rebuilds the filename
   * after the last {@code /-/} segment from the version's own {@code name} and {@code version} and
   * leaves everything before it untouched. That is a no-op for an already-correct URL, and it still
   * strips a scope a client duplicated into the filename (e.g. {@code /-/@foo/demo-0.2.1.tgz}
   * becomes {@code /-/demo-0.2.1.tgz}). A URL with no {@code /-/} is left alone.
   */
  public static void fixTarballUrl(final Map<String, Object> version) throws URISyntaxException {

    final var dist = (Map<String, String>) version.get("dist");
    final var uri = new URI(dist.get("tarball"));
    final var rawPath = uri.getRawPath();

    final var idx = rawPath.lastIndexOf("/-/");

    if (idx < 0) {
      return; // nothing recognisable to fix; leave the client's URL alone
    }

    final var packageName = (String) version.get("name");
    final var versionName = (String) version.get("version");
    final var fileName =
        PackageUtils.getTarballFilename(PackageUtils.bareName(packageName), versionName);

    dist.put(
        "tarball",
        uri.getScheme()
            + "://"
            + uri.getAuthority()
            + rawPath.substring(0, idx)
            + "/-/"
            + fileName);
  }

  private static String bareName(final String packageName) {

    final var slash = packageName.indexOf('/');

    return slash < 0 ? packageName : packageName.substring(slash + 1);
  }

  /** The full package name a URL's scope and package name denote, e.g. {@code @scope/name}. */
  public static String buildFullName(final @Nullable String scopeName, final String packageName) {

    return scopeName == null ? packageName : "@" + scopeName + "/" + packageName;
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

    final var expected = PackageUtils.buildFullName(scopeName, packageName);

    PackageUtils.checkNameMatches(expected, payload.get(NpmConstants.NAME));
    PackageUtils.checkNameMatches(expected, payload.get(NpmConstants.ID));

    if (payload.get(NpmConstants.VERSIONS) instanceof final Map<?, ?> versions) {
      for (final var entry : versions.values()) {
        if (entry instanceof final Map<?, ?> version) {
          PackageUtils.checkNameMatches(expected, version.get(NpmConstants.NAME));
        }
      }
    }
  }

  private static void checkNameMatches(final String expected, final @Nullable Object actual) {

    if (actual instanceof final String name && !expected.equals(name)) {
      throw new BadRequestException("packageNameMismatch");
    }
  }

  public static String getFormattedCurrentTime() {

    final var formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    final var now = LocalDateTime.now(ZoneId.systemDefault());

    return formatter.format(now);
  }

  public static String getLatestVersion(final Map<String, Object> payload)
      throws ClassCastException {

    final var distTags = (Map<String, String>) payload.get("dist-tags");

    return distTags.get("latest");
  }

  public static String getTarballFilename(final String packageName, final String versionName) {

    return packageName + "-" + versionName + "." + TARBALL_EXTENSION;
  }

  public static boolean isRequestedAbbreviatedMetadata(final String acceptHeader) {

    final var headers = acceptHeader.split(";", -1);

    for (final var header : headers) {
      if (header.trim().equals(ABBREVIATED_METADATA_HEADER_VALUE)) {
        return true;
      }
    }

    return false;
  }

  /**
   * With every new latest version published some fields of the version object must be lifted up to
   * the top level <a href=
   * "https://github.com/npm/registry/blob/master/docs/responses/package-metadata.md#full-metadata-format">...</a>
   */
  public static void liftFieldsToTopLevel(
      final Map<String, Object> payload, final String versionName) {

    final var versionPair = PackageUtils.extractVersionFromPayload(payload, versionName);

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

  public static Map<String, Object> readMetadataFromResource(final Resource resource)
      throws IOException {

    final var mapper = new ObjectMapper();

    final var fileContent = resource.getContentAsString(StandardCharsets.UTF_8);

    return mapper.readValue(fileContent, new TypeReference<>() {});
  }

  public static void removeAllTagsPointingToVersion(
      final Map<String, Object> metadata, final String versionName) {

    final var distTags = (Map<String, String>) metadata.get("dist-tags");

    distTags.entrySet().removeIf(entry -> entry.getValue().equals(versionName));
  }

  public static String resolveLatestVersion(final Map<String, Object> metadata) {

    final var versions = (Map<String, Object>) metadata.get(NpmConstants.VERSIONS);

    return resolveLatestVersion(versions.keySet());
  }

  /** The highest of the version names by semver, or an empty string when there are none. */
  public static String resolveLatestVersion(final Collection<String> versionNames) {

    if (versionNames.isEmpty()) {
      return "";
    }

    String latestVersion = null;
    NpmSemver latestSemver = null;

    for (final var key : versionNames) {
      final var semver = NpmSemver.parse(key);

      if (latestSemver == null || semver.compareTo(latestSemver) > 0) {
        latestSemver = semver;
        latestVersion = key;
      }
    }

    return latestVersion;
  }

  public static void updateDistFields(
      final Map<String, Object> metadata, final String versionName, final byte[] tarballBytes) {

    final var shasum = DigestUtils.sha1Hex(tarballBytes);
    final var integrity =
        "sha512-" + Base64.encodeBase64String(DigestUtils.getSha512Digest().digest(tarballBytes));

    final var versions = (Map<String, Object>) metadata.get(NpmConstants.VERSIONS);
    final var version = (Map<String, Object>) versions.get(versionName);

    final var dist = (Map<String, Object>) version.get("dist");

    dist.put("shasum", shasum);
    dist.put("integrity", integrity);
  }
}
