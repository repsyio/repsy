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

import io.repsy.protocols.npm.shared.constants.NpmConstants;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.experimental.UtilityClass;
import org.apache.commons.codec.digest.DigestUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@SuppressWarnings("unchecked")
@UtilityClass
/**
 * The stored and served packument: cleanup, validators, the abbreviated document's accept header.
 */
public final class NpmMetadataUtils {
  private static final ObjectMapper ETAG_MAPPER = new ObjectMapper();
  private static final String ABBREVIATED_METADATA_HEADER_VALUE =
      "application/vnd.npm.install-v1+json";

  public static void updateModifiedTime(final Map<String, Object> metadata) {
    final var timeField = (Map<String, String>) metadata.get("time");

    if (timeField != null) {
      timeField.put("modified", NpmPackageUtils.getFormattedCurrentTime());
    }
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

  public static Map<String, Object> readMetadataFromResource(final Resource resource)
      throws IOException {

    final var mapper = new ObjectMapper();

    final var fileContent = resource.getContentAsString(StandardCharsets.UTF_8);

    return mapper.readValue(fileContent, new TypeReference<>() {});
  }

  public static void removeAllTagsPointingToVersion(
      final Map<String, Object> metadata, final String versionName) {

    final var distTags = Objects.requireNonNull((Map<String, String>) metadata.get("dist-tags"));

    distTags.entrySet().removeIf(entry -> entry.getValue().equals(versionName));
  }

  /** What the abbreviated document carries of a version when the version has it. */
  private static final List<String> ABBREVIATED_OPTIONAL_FIELDS =
      List.of(
          NpmConstants.DEPRECATED,
          NpmConstants.HAS_SHRINKWRAP,
          "os",
          "cpu",
          "libc",
          "peerDependenciesMeta",
          "hasInstallScript",
          "funding",
          "acceptDependencies");

  /** The lifecycle scripts that run on install, which is what {@code hasInstallScript} says. */
  private static final List<String> INSTALL_SCRIPTS =
      List.of("preinstall", "install", "postinstall");

  public static Map<String, Object> createAbbreviatedMetadata(
      final Map<String, Object> fullMetadata) {

    final var abbreviatedMetadata = new LinkedHashMap<String, Object>();

    abbreviatedMetadata.put("name", fullMetadata.get("name"));
    abbreviatedMetadata.put(NpmConstants.DIST_TAGS, fullMetadata.get(NpmConstants.DIST_TAGS));

    final var timeField = (Map<String, String>) fullMetadata.get("time");

    if (timeField != null) {
      abbreviatedMetadata.put(NpmConstants.MODIFIED, timeField.get(NpmConstants.MODIFIED));
    }

    final var abbreviatedVersions = new LinkedHashMap<String, Object>();
    final var versions = (Map<String, Object>) fullMetadata.get(NpmConstants.VERSIONS);

    if (versions != null) {
      for (final var entry : versions.entrySet()) {
        abbreviatedVersions.put(
            entry.getKey(), abbreviateVersion((Map<String, Object>) entry.getValue()));
      }
    }

    abbreviatedMetadata.put(NpmConstants.VERSIONS, abbreviatedVersions);
    return abbreviatedMetadata;
  }

  private static Map<String, Object> abbreviateVersion(final Map<String, Object> version) {

    final var emptyHashMap = new HashMap<String, Object>();
    final var abbreviatedVersion = new LinkedHashMap<String, Object>();

    // Required fields
    abbreviatedVersion.put("name", version.get("name"));
    abbreviatedVersion.put("version", version.get("version"));
    abbreviatedVersion.put("dist", version.get("dist"));

    // Optional fields that have no default value: what a client decides on before it has the
    // tarball, such as where a version installs and which peers are optional (RPS-1356)
    for (final var field : ABBREVIATED_OPTIONAL_FIELDS) {
      if (version.get(field) != null) {
        abbreviatedVersion.put(field, version.get(field));
      }
    }

    // The public registry derives this from the scripts of the version, so a version that was
    // published without the flag (an older client) still tells the installer to expect a script
    if (hasInstallScript(version)) {
      abbreviatedVersion.put("hasInstallScript", true);
    }

    // Optional fields that have default values
    abbreviatedVersion.put("dependencies", version.getOrDefault("dependencies", emptyHashMap));
    abbreviatedVersion.put(
        "devDependencies", version.getOrDefault("devDependencies", emptyHashMap));
    abbreviatedVersion.put(
        "optionalDependencies", version.getOrDefault("optionalDependencies", emptyHashMap));
    abbreviatedVersion.put(
        "peerDependencies", version.getOrDefault("peerDependencies", emptyHashMap));
    abbreviatedVersion.put(
        "bundleDependencies", version.getOrDefault("bundleDependencies", new ArrayList<>()));
    abbreviatedVersion.put("bin", version.getOrDefault("bin", emptyHashMap));
    abbreviatedVersion.put("directories", version.getOrDefault("directories", emptyHashMap));
    abbreviatedVersion.put("engines", version.getOrDefault("engines", emptyHashMap));

    return abbreviatedVersion;
  }

  /**
   * Whether the version installs with a script: the stored flag, or a non-blank {@code preinstall},
   * {@code install} or {@code postinstall} in its {@code scripts} (RPS-1390).
   */
  private static boolean hasInstallScript(final Map<String, Object> version) {
    if (Boolean.TRUE.equals(version.get("hasInstallScript"))) {
      return true;
    }

    if (!(version.get("scripts") instanceof final Map<?, ?> scripts)) {
      return false;
    }

    return INSTALL_SCRIPTS.stream()
        .anyMatch(name -> scripts.get(name) instanceof final String script && !script.isBlank());
  }
}
