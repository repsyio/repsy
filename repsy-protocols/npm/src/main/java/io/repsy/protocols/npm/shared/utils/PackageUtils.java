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
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
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
  private static final ObjectMapper ETAG_MAPPER = new ObjectMapper();
  private static final String TARBALL_EXTENSION = "tgz";
  private static final String ABBREVIATED_METADATA_HEADER_VALUE =
      "application/vnd.npm.install-v1+json";

  public static void updateModifiedTime(final Map<String, Object> metadata) {
    final var timeField = (Map<String, String>) metadata.get("time");

    if (timeField != null) {
      timeField.put("modified", PackageUtils.getFormattedCurrentTime());
    }
  }

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

    final var oldVersions = (Map<String, Object>) oldMetadata.get(NpmConstants.VERSIONS);

    if (!(newMetadata.get(NpmConstants.VERSIONS) instanceof final Map<?, ?> newVersions)) {
      throw new BadRequestException("badRequest");
    }

    final var missing =
        oldVersions.keySet().stream().filter(name -> !newVersions.containsKey(name)).toList();

    if (missing.size() != 1) {
      throw new ItemAlreadyExistException("unpublishPayloadStale");
    }

    return missing.getFirst();
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

  /**
   * The address of a version's tarball in the registry: {@code <base>/<repoName>/<fullName>/-/<bare
   * name>-<version>.tgz}, the layout every client and {@code
   * AbstractNpmPackageDownloadProtocolMethodHandler} agree on. A trailing slash on {@code base} is
   * dropped, and the base may carry a path prefix.
   */
  public static String buildTarballUrl(
      final String base, final String repoName, final String fullName, final String versionName) {

    return base.replaceAll("/+$", "")
        + "/"
        + repoName
        + "/"
        + fullName
        + "/-/"
        + getTarballFilename(bareName(fullName), versionName);
  }

  /**
   * Points every version's {@code dist.tarball} at the registry address {@code base}, whatever the
   * publisher sent (its host, its port, the {@code http://} that libnpmpublish and yarn classic
   * write for an HTTPS registry). Clients fetch the URL as served and withhold their credentials
   * from another origin, so the registry has to name itself, as every other npm registry does. A
   * version with no {@code dist.tarball} is left as it is, and a version without a {@code name} of
   * its own takes the package's.
   */
  public static void rewriteTarballUrls(
      final Map<String, Object> metadata, final String base, final String repoName) {

    if (!(metadata.get(NpmConstants.VERSIONS) instanceof final Map<?, ?> versions)) {
      return;
    }

    final var packageName = metadata.get(NpmConstants.NAME);

    for (final var entry : versions.entrySet()) {
      if (entry.getKey() instanceof final String versionName
          && entry.getValue() instanceof final Map<?, ?> version) {
        rewriteTarballUrl(version, packageName, versionName, base, repoName);
      }
    }
  }

  private static void rewriteTarballUrl(
      final Map<?, ?> version,
      final @Nullable Object packageName,
      final String versionName,
      final String base,
      final String repoName) {

    final var fullName =
        version.get(NpmConstants.NAME) instanceof final String own ? own : packageName;

    if (version.get("dist") instanceof final Map<?, ?> dist
        && dist.containsKey(NpmConstants.TARBALL)
        && fullName instanceof final String name) {
      ((Map<String, Object>) dist)
          .put(NpmConstants.TARBALL, buildTarballUrl(base, repoName, name, versionName));
    }
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

  /**
   * Whether the {@code Accept} header asks for the abbreviated install document rather than the
   * full packument: it lists {@code application/vnd.npm.install-v1+json} with a quality above zero
   * that is not lower than that of {@code application/json}. So {@code pnpm}'s {@code
   * application/vnd.npm.install-v1+json; q=1.0, application/json; q=0.8} is abbreviated at any
   * position of the list, a client that prefers {@code application/json} is served the full one,
   * and an explicit {@code q=0} refuses the abbreviated document.
   */
  public static boolean isRequestedAbbreviatedMetadata(final String acceptHeader) {

    var abbreviated = 0.0;
    var full = 0.0;

    for (final var entry : acceptHeader.split(",", -1)) {
      final var parts = entry.split(";", -1);
      final var mediaType = parts[0].trim();
      final var quality = qualityOf(parts);

      if (mediaType.equalsIgnoreCase(ABBREVIATED_METADATA_HEADER_VALUE)) {
        abbreviated = Math.max(abbreviated, quality);
      } else if (mediaType.equalsIgnoreCase("application/json")) {
        full = Math.max(full, quality);
      }
    }

    return abbreviated > 0 && abbreviated >= full;
  }

  /** The {@code q} parameter of an {@code Accept} entry (1 when it has none or a broken one). */
  private static double qualityOf(final String[] entryParts) {

    for (var i = 1; i < entryParts.length; i++) {
      final var parameter = entryParts[i].trim();

      if (parameter.regionMatches(true, 0, "q=", 0, 2)) {
        try {
          return Double.parseDouble(parameter.substring(2).trim());
        } catch (final NumberFormatException _) {
          return 1.0;
        }
      }
    }

    return 1.0;
  }

  /**
   * Removes what a publish carries that a packument does not keep: the {@code _attachments} (the
   * base64 tarball of the publish), and the {@code _from} and {@code _resolved} that {@code npm
   * publish <tarball>} adds to the manifest (the publisher's own file path), at the top and in
   * every version. The public registry serves none of them (RPS-1357).
   */
  public static void removePublishOnlyFields(final Map<String, Object> metadata) {

    metadata.remove(NpmConstants.ATTACHMENTS);
    metadata.remove(NpmConstants.FROM);
    metadata.remove(NpmConstants.RESOLVED);

    if (metadata.get(NpmConstants.VERSIONS) instanceof final Map<?, ?> versions) {
      for (final var version : versions.values()) {
        if (version instanceof final Map<?, ?> versionMetadata) {
          versionMetadata.remove(NpmConstants.FROM);
          versionMetadata.remove(NpmConstants.RESOLVED);
        }
      }
    }
  }

  /**
   * Removes the {@code deprecated} of every version that has an empty one: that is what {@code npm
   * deprecate pkg@x ""} used to store, and the registry's own meaning of an empty message is "no
   * longer deprecated". A client that finds the field at all (pnpm) treats the version as
   * deprecated (RPS-1360).
   */
  public static void removeEmptyDeprecations(final Map<String, Object> metadata) {

    if (metadata.get(NpmConstants.VERSIONS) instanceof final Map<?, ?> versions) {
      for (final var version : versions.values()) {
        if (version instanceof final Map<?, ?> versionMetadata
            && "".equals(versionMetadata.get(NpmConstants.DEPRECATED))) {
          versionMetadata.remove(NpmConstants.DEPRECATED);
        }
      }
    }
  }

  /**
   * A weak entity tag of the document: the SHA-256 of its JSON, so it differs between the
   * abbreviated and the full document, and between two addresses of one registry (the {@code
   * dist.tarball} of every version is in it). It is weak because the same document is also sent
   * compressed, which is another representation of it (and the container does not compress an
   * answer that has a strong tag, whereas a weak one is what a conditional request needs anyway).
   */
  public static String computeEtag(final Map<String, Object> metadata) {

    final var digest = DigestUtils.getSha256Digest();

    try (final var out = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
      ETAG_MAPPER.writeValue(out, metadata);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    return "W/\"" + HexFormat.of().formatHex(digest.digest()) + "\"";
  }

  /**
   * When the package last changed: the {@code time.modified} of the full packument, or the {@code
   * modified} of the abbreviated one. {@code null} when there is none, or it is not a date.
   */
  public static @Nullable Instant lastModifiedOf(final Map<String, Object> metadata) {

    var modified = metadata.get(NpmConstants.MODIFIED);

    if (modified == null && metadata.get("time") instanceof final Map<?, ?> time) {
      modified = time.get(NpmConstants.MODIFIED);
    }

    if (modified instanceof final String text) {
      try {
        return Instant.parse(text);
      } catch (final DateTimeParseException _) {
        return null;
      }
    }

    return null;
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
