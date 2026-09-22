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

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.maven.shared.artifact.services.VersionComparator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.apache.maven.index.artifact.Gav;
import org.apache.maven.index.artifact.M2GavCalculator;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;

@Slf4j
@UtilityClass
@NullMarked
public class ArtifactUtils {

  private static final String METADATA_FILENAME = "maven-metadata.xml";
  private static final String MAVEN_PLUGIN = "maven-plugin";
  private static final String POM_SUFFIX = ".pom";
  private static final String SIGNED_POM_SUFFIX = ".asc";
  private static final Set<String> CHECKSUM_TYPES = Set.of(".md5", ".sha1", ".sha256", ".sha512");
  private static final String SNAPSHOT_SUFFIX = "SNAPSHOT";
  private static final String SNAPSHOT_MARKER = "(SNAPSHOT|\\d{8}\\.\\d{6}-\\d+)[.-]";

  public static boolean containsIgnoreCase(final String str, final String subString) {

    return str.toLowerCase(Locale.getDefault())
        .contains(subString.toLowerCase(Locale.getDefault()));
  }

  @Nullable
  private static Gav convertPathToBasicGav(final String str) {

    final var s = str.startsWith("/") ? str.substring(1) : str;

    final var vEndPos = s.lastIndexOf('/');

    if (vEndPos == -1) {
      return null;
    }

    final var aEndPos = s.lastIndexOf('/', vEndPos - 1);

    if (aEndPos == -1) {
      return null;
    }

    final var gEndPos = s.lastIndexOf('/', aEndPos - 1);

    if (gEndPos == -1) {
      return null;
    }

    final var groupId = s.substring(0, gEndPos).replace('/', '.');
    final var artifactId = s.substring(gEndPos + 1, aEndPos);
    final var version = s.substring(aEndPos + 1, vEndPos);

    return new Gav(groupId, artifactId, version);
  }

  @Nullable
  public static Gav convertPathToGav(final String path) {

    try {
      final var gav = new M2GavCalculator().pathToGav(path);

      if (gav == null || isSnapshotFileOfItsDirectory(path)) {
        return gav;
      }

      log.debug(
          "No Maven GAV for {}: the file name does not carry the artifactId and base version of"
              + " its SNAPSHOT directory",
          path);
      return null;
    } catch (final RuntimeException e) {
      // M2GavCalculator throws IndexOutOfBoundsException for a file in a snapshot directory whose
      // name is shorter than the artifactId (com/acme/lib/1.0-SNAPSHOT/b-1.0-SNAPSHOT.jar). Such a
      // path is not a Maven path, exactly like the ones it answers null for.
      log.debug("No Maven GAV for {}: {}", path, e.toString());
      return null;
    }
  }

  /**
   * Tells whether a file sits in a directory it may be named for. {@code M2GavCalculator} only
   * checks, for a directory ending with {@code SNAPSHOT}, that the {@code SNAPSHOT} marker or a
   * {@code yyyyMMdd.HHmmss-N} timestamp sits where {@code <artifactId>-<baseVersion>-} would end;
   * it compares neither the artifactId nor the version, so {@code lib-2.0-SNAPSHOT.jar} in {@code
   * com/acme/lib/1.0-SNAPSHOT/} would be registered as {@code lib:1.0-SNAPSHOT}. A release
   * directory refuses the same mistakes.
   *
   * <p>The rule is the one of the Maven repository layout (Maven Resolver's {@code
   * Maven2RepositoryLayoutFactory}, Gradle and sbt write the same names): the directory is the base
   * version and the file name starts with {@code <artifactId>-<version>}, where the version is the
   * base version (a non-unique snapshot, the literal {@code SNAPSHOT}) or the base version with
   * {@code SNAPSHOT} replaced by {@code <yyyyMMdd.HHmmss>-<buildNumber>} (a unique snapshot, {@code
   * 1.0-SNAPSHOT} becomes {@code 1.0-20260921.101010-1}, {@code SNAPSHOT} becomes {@code
   * 20260921.101010-1}). A classifier, an extension, a checksum or a signature may follow, so the
   * raw file name is only tested as a prefix. The comparison is case-sensitive like the release
   * one.
   *
   * <p>It is decided on the directory and not on {@code Gav.isSnapshot()}: for {@code
   * lib-1.0-2026092.1010101-1.jar} the calculator answers a GAV that is not a snapshot.
   *
   * <p>RPS-1184.
   *
   * @param path A path the calculator already answered a GAV for, so it has at least four segments
   * @return {@code true} if the directory is not a {@code SNAPSHOT} one or the file is named for it
   */
  private static boolean isSnapshotFileOfItsDirectory(final String path) {

    final var segments = path.split("/", -1);
    final var last = segments.length - 1;
    final var fileName = segments[last];
    final var version = segments[last - 1];
    final var artifactId = segments[last - 2];

    if (!version.endsWith(SNAPSHOT_SUFFIX)) {
      return true;
    }

    final var stem = version.substring(0, version.length() - SNAPSHOT_SUFFIX.length());

    return Pattern.compile(Pattern.quote(artifactId + "-" + stem) + SNAPSHOT_MARKER)
        .matcher(fileName)
        .lookingAt();
  }

  public static @Nullable Gav getGavByFile(final StoragePath storagePath) {

    if (ArtifactUtils.containsIgnoreCase(
        storagePath.getRelativePath().getPath(), METADATA_FILENAME)) {
      return ArtifactUtils.convertPathToBasicGav(storagePath.getRelativePath().getPath());
    } else {
      return ArtifactUtils.convertPathToGav(storagePath.getRelativePath().getPath());
    }
  }

  public static String getPrefixFromArtifactId(final String artifactId) {

    if ("maven-plugin-plugin".equals(artifactId)) {
      return "plugin";
    } else {
      return artifactId.replaceAll("-?maven-?", "").replaceAll("-?plugin-?", "");
    }
  }

  public static boolean isSnapshot(final String versionName) {

    return org.apache.maven.artifact.ArtifactUtils.isSnapshot(versionName);
  }

  @Nullable
  public static Metadata readMetadata(final byte[] content)
      throws IOException, XmlPullParserException {

    final var reader = new MetadataXpp3Reader();

    try {
      return reader.read(new ByteArrayInputStream(content), false);
    } catch (final IOException | XmlPullParserException e) {
      log.warn("Malformed or incomplete maven-metadata.xml received: {}", e.getMessage());
      throw new BadRequestException("malformedMetadataFile");
    }
  }

  @Nullable
  public static Model readModel(final Resource pomResource) {

    try (final var inputStream = pomResource.getInputStream()) {
      return readModel(inputStream);
    } catch (final IOException e) {
      log.warn("Malformed or unreadable POM file received: {}", e.getMessage());
      throw new BadRequestException("malformedPomFile");
    }
  }

  /**
   * Parses a POM without closing the stream.
   *
   * @throws BadRequestException With the fixed {@code malformedPomFile} id if the POM cannot be
   *     read or parsed
   */
  @Nullable
  public static Model readModel(final InputStream pomStream) {

    final var reader = new MavenXpp3Reader();

    try {
      return reader.read(new InputStreamReader(pomStream, UTF_8));
    } catch (final IOException | XmlPullParserException e) {
      log.warn("Malformed or unreadable POM file received: {}", e.getMessage());
      throw new BadRequestException("malformedPomFile");
    }
  }

  /** The groupId a POM declares: its own, else its parent's (Maven inherits it), else null. */
  public static @Nullable String declaredGroupId(final Model model) {

    if (model.getGroupId() != null) {
      return model.getGroupId();
    }

    return model.getParent() != null ? model.getParent().getGroupId() : null;
  }

  /**
   * Refuses a POM whose declared groupId is not the group of its path. The artifact service
   * registers a version under the group of the path, so a POM of another group used to be stored
   * and answered 200 but never registered: served, yet invisible and undeletable in the panel and
   * out of reach of the version events and the scanner (RPS-1193).
   *
   * <p>There is no check when the POM declares no groupId at all (Maven refuses such a POM itself)
   * or when the path has no GAV (it is refused earlier as {@code invalidArtifactPath}). The
   * comparison is case-sensitive like the repository layout, and the artifactId and the version are
   * not compared: they are never used for the registration, and {@code ${revision}}, an inherited
   * version or an sbt cross-versioned artifactId would be refused wrongly.
   *
   * @param model The parsed POM, {@code null} when there is none to check
   * @param path The repository-relative path the POM is uploaded to
   * @throws BadRequestException With the fixed {@code pomGroupIdMismatch} id
   */
  public static void checkPomGroupIdMatchesPath(final @Nullable Model model, final String path) {

    final var gav = convertPathToGav(path);

    if (model == null || gav == null) {
      return;
    }

    final var declared = declaredGroupId(model);

    if (declared != null && !declared.equals(gav.getGroupId())) {
      log.info(
          "Refusing POM {}: it declares groupId {} under group {}",
          path,
          declared,
          gav.getGroupId());
      throw new BadRequestException("pomGroupIdMismatch");
    }
  }

  private static boolean endsWithIgnoreCase(final String str, final String suffix) {
    return str.length() >= suffix.length()
        && str.regionMatches(true, str.length() - suffix.length(), suffix, 0, suffix.length());
  }

  /**
   * Tells a POM by its file name alone: it ends with {@code .pom} (any case). A checksum or an
   * {@code .asc} of it does not, and a directory or artifactId containing {@code .pom} is not
   * looked at (RPS-1196).
   */
  public static boolean isPomFile(final String fileName) {
    return endsWithIgnoreCase(fileName, POM_SUFFIX);
  }

  public static boolean isPomToParse(final StoragePath storagePath) {
    return isPomFile(storagePath.getRelativePath().getFileName());
  }

  /**
   * The {@code .asc} (case-sensitive, like {@link #isMetadataSignature}) of a file that {@link
   * #isPomFile}.
   */
  public static boolean isPomSignature(final StoragePath storagePath) {
    final var fileName = storagePath.getRelativePath().getFileName();
    return fileName.endsWith(SIGNED_POM_SUFFIX)
        && isPomFile(fileName.substring(0, fileName.length() - SIGNED_POM_SUFFIX.length()));
  }

  public static void setReleaseAndLatest(final Metadata metadata) {

    // sorts the versions and finds real release and latest versions not to put last
    // updated version.
    sortVersions(metadata);

    final var versioning = metadata.getVersioning();

    if (versioning == null || metadata.getVersioning().getVersions().isEmpty()) {
      return;
    }

    final var latest = versioning.getVersions().getLast();

    String release = null;

    for (int i = versioning.getVersions().size() - 1; i >= 0; i--) {
      if (!ArtifactUtils.isSnapshot(versioning.getVersions().get(i))) {
        release = versioning.getVersions().get(i);
        break;
      }
    }

    versioning.setLatest(latest);
    versioning.setRelease(release);
  }

  public static boolean artifactIsPlugin(final Model model) {

    return MAVEN_PLUGIN.equalsIgnoreCase(model.getPackaging());
  }

  private static void sortVersions(final Metadata metadata) {

    if (metadata.getVersioning() == null) {
      return;
    }

    if (metadata.getVersioning().getVersions() != null) {
      metadata.getVersioning().getVersions().sort(new VersionComparator());
    }
  }

  /**
   * Tells the group-level {@code maven-metadata.xml} Maven writes for plugin deploys. {@code
   * Metadata.getPlugins()} never returns {@code null}: it creates an empty list on first access, so
   * the presence of at least one plugin is what makes the file plugin metadata.
   */
  public static boolean isPluginMetadata(final @Nullable Metadata metadata) {

    return metadata != null && !metadata.getPlugins().isEmpty();
  }

  /**
   * Tells the version-level {@code maven-metadata.xml}, the {@code g/a/<baseVersion>/} file Maven
   * writes for snapshot deploys. It is the only metadata file that names a single version, in its
   * {@code <version>} element; the artifact-level file lists every version and the group-level file
   * lists plugins, and neither has one.
   */
  public static boolean isVersionLevelMetadata(final @Nullable Metadata metadata) {

    return metadata != null && metadata.getVersion() != null && !metadata.getVersion().isBlank();
  }

  public static boolean isChecksumFile(final String fileName) {

    final var dotIndex = fileName.lastIndexOf('.');

    return CHECKSUM_TYPES.contains(
        ((dotIndex == -1) ? "" : fileName.substring(dotIndex)).toLowerCase(Locale.getDefault()));
  }

  /**
   * Tells the detached PGP signature of a {@code maven-metadata.xml}: {@code
   * maven-metadata.xml.asc} at any level. No official client writes one (maven-gpg-plugin, Maven
   * Resolver and Gradle sign artifacts only), but Maven Central serves them and Nexus stores them
   * as a subordinate of the metadata, like a checksum. Its body is armored text, not XML, so it is
   * never parsed and is judged by its directory like a metadata checksum (RPS-1185). A checksum of
   * it ({@code .asc.sha1}) is a checksum. The suffix is matched case-sensitively like {@link
   * #isPomSignature}.
   */
  public static boolean isMetadataSignature(final String fileName) {

    return containsIgnoreCase(fileName, METADATA_FILENAME) && fileName.endsWith(SIGNED_POM_SUFFIX);
  }

  /**
   * Tells whether a file sits in a {@code SNAPSHOT} version directory: the second-to-last segment
   * of the path ends with {@code SNAPSHOT}, the same rule as {@code isSnapshotFileOfItsDirectory}
   * and the GAV calculator's. It is how a version-level {@code maven-metadata.xml} checksum is told
   * from the artifact-level and group-level ones, as its body is a hash and holds no {@code
   * <version>} (RPS-1183).
   */
  public static boolean isSnapshotVersionDirectoryFile(final String path) {

    final var segments = path.split("/", -1);
    final var directoryIndex = segments.length - 2;

    return directoryIndex >= 0 && segments[directoryIndex].endsWith(SNAPSHOT_SUFFIX);
  }

  public static boolean isFileSuitableForGavExtraction(final String fileName) {

    // Condition for detection metadata files, their checksums and their signatures.
    if (ArtifactUtils.containsIgnoreCase(fileName, METADATA_FILENAME)) {
      return isSnapshot(fileName);
    }

    return true;
  }
}
