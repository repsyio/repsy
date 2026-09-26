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
package io.repsy.protocols.maven.shared.utils;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.repsy.protocols.maven.shared.artifact.dtos.RegisteredPlugin;
import io.repsy.protocols.maven.shared.artifact.dtos.RegisteredVersion;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import lombok.experimental.UtilityClass;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.Plugin;
import org.apache.maven.artifact.repository.metadata.Versioning;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Writer;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Builds the artifact-level {@code maven-metadata.xml} of an artifact whose client stored none, and
 * the checksums of it (RPS-1369), and the group-level one that lists the plugins of a group
 * (RPS-1438). sbt and Ivy publish no metadata at all, so without it Maven's {@code LATEST} and
 * version ranges, Gradle's {@code 1.+} and sbt's {@code latest.release} find no version to pick.
 *
 * <p>The answer is a function of the registered versions alone: the versions are sorted, {@code
 * latest} and {@code release} are told like {@link ArtifactUtils#setReleaseAndLatest} does for the
 * file a delete rewrites, and {@code lastUpdated} is the newest time a version was registered,
 * never the time of the request. So two requests with no change in between are answered with the
 * same bytes, and a checksum fetched after its file matches it. The group-level file has no time at
 * all, so it holds that too.
 */
@UtilityClass
@NullMarked
public class ArtifactMetadataSynthesizer {

  private static final String METADATA_FILENAME = "maven-metadata.xml";
  private static final String SNAPSHOT_SUFFIX = "SNAPSHOT";

  /** The checksum algorithms of a metadata file, by their file name extension. */
  private static final Map<String, Function<byte[], String>> CHECKSUMS =
      Map.of(
          "md5", DigestUtils::md5Hex,
          "sha1", DigestUtils::sha1Hex,
          "sha256", DigestUtils::sha256Hex,
          "sha512", DigestUtils::sha512Hex);

  /**
   * The artifact-level metadata file a path asks for.
   *
   * @param groupId the group, the directories ahead of the artifact joined by dots
   * @param artifactId the last directory
   * @param checksumAlgorithm {@code md5}, {@code sha1}, {@code sha256} or {@code sha512} for a
   *     checksum of the file, {@code null} for the file itself
   */
  public record Request(String groupId, String artifactId, @Nullable String checksumAlgorithm) {

    /** The name of the file asked for. */
    public String fileName() {
      return this.checksumAlgorithm == null
          ? METADATA_FILENAME
          : METADATA_FILENAME + "." + this.checksumAlgorithm;
    }

    /** The path (without a leading slash) of the file that the checksum is a checksum of. */
    public String metadataPath() {
      return this.groupId.replace('.', '/') + "/" + this.artifactId + "/" + METADATA_FILENAME;
    }
  }

  /**
   * The group-level metadata file a path asks for.
   *
   * @param groupId the group, all directories ahead of the file joined by dots
   * @param checksumAlgorithm as in {@link Request}
   */
  public record GroupRequest(String groupId, @Nullable String checksumAlgorithm) {

    /** The name of the file asked for. */
    public String fileName() {
      return this.checksumAlgorithm == null
          ? METADATA_FILENAME
          : METADATA_FILENAME + "." + this.checksumAlgorithm;
    }

    /** The path (without a leading slash) of the file that the checksum is a checksum of. */
    public String metadataPath() {
      return this.groupId.replace('.', '/') + "/" + METADATA_FILENAME;
    }
  }

  /**
   * Tells whether a path is the artifact-level {@code maven-metadata.xml} (or one of its {@code
   * .md5}, {@code .sha1}, {@code .sha256} and {@code .sha512} checksums, the only ones a client
   * asks for) and of which artifact.
   *
   * <p>Nothing else is answered: not a signature ({@code .asc}, it cannot be signed), not a file of
   * a {@code SNAPSHOT} version directory (the version-level metadata), and not a path with no group
   * ahead of the artifact. Names are matched case-sensitively, like the clients ask for them. A
   * path with the shape of a group-level or release version-level metadata file cannot be told from
   * an artifact-level one, so it is one, and it is answered only if an artifact of exactly that
   * group and name is registered. The caller then tries {@link #parseGroupLevel} for the same path,
   * so the artifact-level file comes first and the group-level one is answered only when no such
   * artifact is registered.
   *
   * @param relativePath the path inside the repo, with or without a leading slash
   * @return {@code null} when the path is not one of those
   */
  public static @Nullable Request parse(final String relativePath) {

    final var path = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;
    final var segments = List.of(path.split("/", -1));

    if (!isArtifactLevelLayout(segments)) {
      return null;
    }

    final var artifactIndex = segments.size() - 2;
    final var artifactId = segments.get(artifactIndex);

    final var algorithm = checksumAlgorithmOf(segments.getLast());

    if (algorithm == null) {
      return null;
    }

    final var groupId = String.join(".", segments.subList(0, artifactIndex));

    return new Request(groupId, artifactId, algorithm.isEmpty() ? null : algorithm);
  }

  /**
   * Tells whether a path is the group-level {@code maven-metadata.xml} (or one of its checksums),
   * the file Maven reads to resolve a plugin prefix ({@code mvn prefix:goal}), and of which group.
   * The same names are answered as for {@link #parse}, and a {@code SNAPSHOT} directory is not a
   * group. The group is every directory ahead of the file, so a group of one segment is accepted
   * here, which {@link #parse} cannot see.
   *
   * @param relativePath the path inside the repo, with or without a leading slash
   * @return {@code null} when the path is not one of those
   */
  public static @Nullable GroupRequest parseGroupLevel(final String relativePath) {

    final var path = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;
    final var segments = List.of(path.split("/", -1));

    if (!isGroupLevelLayout(segments)) {
      return null;
    }

    final var algorithm = checksumAlgorithmOf(segments.getLast());

    if (algorithm == null) {
      return null;
    }

    final var groupId = String.join(".", segments.subList(0, segments.size() - 1));

    return new GroupRequest(groupId, algorithm.isEmpty() ? null : algorithm);
  }

  /**
   * A group of at least one segment and the file, none of them empty, and the last directory is not
   * a {@code SNAPSHOT} version directory.
   */
  private static boolean isGroupLevelLayout(final List<String> segments) {

    final var minSegments = 2;

    return segments.size() >= minSegments
        && !segments.contains("")
        && !segments.get(segments.size() - 2).endsWith(SNAPSHOT_SUFFIX);
  }

  /**
   * A group of at least one segment, the artifact and the file, none of them empty, and the last
   * directory is not a {@code SNAPSHOT} version directory (that is where the version-level file
   * is).
   */
  private static boolean isArtifactLevelLayout(final List<String> segments) {

    final var minSegments = 3;

    return segments.size() >= minSegments
        && !segments.contains("")
        && !segments.get(segments.size() - 2).endsWith(SNAPSHOT_SUFFIX);
  }

  /**
   * The extension of a checksum of the metadata file, {@code ""} for the file itself and {@code
   * null} for any other name.
   */
  private static @Nullable String checksumAlgorithmOf(final String fileName) {

    if (fileName.equals(METADATA_FILENAME)) {
      return "";
    }

    final var checksumPrefix = METADATA_FILENAME + ".";

    if (!fileName.startsWith(checksumPrefix)) {
      return null;
    }

    final var algorithm = fileName.substring(checksumPrefix.length());

    return CHECKSUMS.containsKey(algorithm) ? algorithm : null;
  }

  /**
   * The {@code maven-metadata.xml} of an artifact with these versions. The caller answers a 404
   * instead when there are none: the file would say nothing.
   *
   * @param versions the versions of the artifact in any order, each once
   */
  public static byte[] metadataXml(
      final String groupId, final String artifactId, final List<RegisteredVersion> versions) {

    final var versioning = new Versioning();
    versioning.setVersions(
        new ArrayList<>(versions.stream().map(RegisteredVersion::versionName).toList()));

    final var metadata = new Metadata();
    metadata.setGroupId(groupId);
    metadata.setArtifactId(artifactId);
    metadata.setVersioning(versioning);

    ArtifactUtils.setReleaseAndLatest(metadata);

    versions.stream()
        .map(RegisteredVersion::lastUpdatedAt)
        .filter(Objects::nonNull)
        .max(Instant::compareTo)
        .ifPresent(newest -> versioning.setLastUpdatedTimestamp(Date.from(newest)));

    return write(metadata);
  }

  private static byte[] write(final Metadata metadata) {

    try (final var out = new ByteArrayOutputStream()) {
      new MetadataXpp3Writer().write(out, metadata);

      return out.toByteArray();
    } catch (final IOException e) {
      // Writing into memory cannot fail.
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The group-level {@code maven-metadata.xml} of a group with these plugins, in the shape Maven
   * writes when it deploys a plugin: a {@code <plugins>} list and nothing else, no {@code groupId},
   * no {@code versioning}. The caller answers a 404 instead when there are none. The plugins are
   * listed in the order given, and a plugin with no name has no {@code <name>}.
   *
   * @param plugins the plugins of the group, each once
   */
  public static byte[] groupMetadataXml(final List<RegisteredPlugin> plugins) {

    final var metadata = new Metadata();

    for (final var registered : plugins) {
      final var plugin = new Plugin();
      plugin.setArtifactId(registered.artifactId());
      plugin.setPrefix(registered.prefix());

      if (registered.name() != null && !registered.name().isBlank()) {
        plugin.setName(registered.name());
      }

      metadata.addPlugin(plugin);
    }

    return write(metadata);
  }

  /**
   * The content of the checksum file of {@code content}: the digest in lower-case hex and nothing
   * else, the way Maven Resolver reads it and the way a delete rewrites one.
   *
   * @param algorithm one of the extensions of {@link Request#checksumAlgorithm()}
   */
  public static byte[] checksum(final byte[] content, final String algorithm) {

    final var digest = CHECKSUMS.get(algorithm);

    if (digest == null) {
      throw new IllegalArgumentException("Unknown checksum algorithm: " + algorithm);
    }

    return digest.apply(content).getBytes(UTF_8);
  }
}
