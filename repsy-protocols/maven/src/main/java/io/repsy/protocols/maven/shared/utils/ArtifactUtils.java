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
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.maven.shared.artifact.services.VersionComparator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
  private static final String SIGNATURE_SUFFIX = ".asc";
  private static final Set<String> CHECKSUM_TYPES = Set.of(".md5", ".sha1", ".sha256", ".sha512");
  private static final String SNAPSHOT_SUFFIX = "SNAPSHOT";
  private static final String SNAPSHOT_MARKER = "(SNAPSHOT|\\d{8}\\.\\d{6}-\\d+)[.-]";
  private static final String SNAPSHOT_BUILD_MARKER = "(?:SNAPSHOT|(\\d{8}\\.\\d{6})-(\\d+))[.-]";
  private static final String SNAPSHOT_MAIN_FILE_MARKER =
      "(?:SNAPSHOT|(\\d{8}\\.\\d{6})-(\\d+))\\.";
  private static final SnapshotBuild LITERAL_SNAPSHOT = new SnapshotBuild("", BigInteger.ZERO);

  /**
   * Tells whether a file is the {@code jar} of the given classifier, for example {@code sources} or
   * {@code javadoc}. The file is parsed like any other Maven path, so only the classifier of its
   * own GAV counts: an artifactId such as {@code foo-sources}, a checksum and a signature of the
   * jar (which are not the jar) and a file that is not in the Maven layout are all {@code false}
   * (RPS-1198). The classifier is compared exactly, like Maven Resolver does.
   *
   * @param relativePath the path of the file inside the repo, {@code
   *     <group>/<artifactId>/<version>/<file>}
   */
  public static boolean isClassifierJar(final String relativePath, final String classifier) {

    final var gav = convertPathToGav(relativePath);

    return gav != null
        && !gav.isHash()
        && !gav.isSignature()
        && classifier.equals(gav.getClassifier())
        && "jar".equals(gav.getExtension());
  }

  /**
   * Parses the basic GAV of a metadata-family path: {@code g/a/<version>/maven-metadata.xml*} for a
   * {@code SNAPSHOT} version directory, the only shape a real client writes at version level
   * (RPS-1195). Any other shape, including {@code g/a/maven-metadata.xml*} (artifact-level) and a
   * hand-crafted {@code g/a/<release>/maven-metadata.xml*} (release version-level), cannot be told
   * apart from path segments alone: a shorter groupId with a version segment looks exactly like a
   * longer groupId without one. Such a path used to be parsed as if it always had a version, which
   * shifted every field by one for the artifact-level case (RPS-1177); it now answers {@code null}
   * instead, the documented gap for the release version-level case (RPS-1195).
   *
   * @param path A path a metadata-family file name ({@link #isMetadataFamilyFile}) was found at
   */
  @Nullable
  private static Gav convertPathToBasicGav(final String path) {

    // group (>=1 segment) + artifactId + version, ahead of the file itself.
    final var minSegmentsBeforeFile = 3;

    final var s = path.startsWith("/") ? path.substring(1) : path;
    final var segments = s.split("/", -1);
    final var fileIndex = segments.length - 1;

    if (fileIndex < minSegmentsBeforeFile || !segments[fileIndex - 1].endsWith(SNAPSHOT_SUFFIX)) {
      return null;
    }

    final var groupId = String.join(".", Arrays.asList(segments).subList(0, fileIndex - 2));
    final var artifactId = segments[fileIndex - 2];
    final var version = segments[fileIndex - 1];

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

    if (ArtifactUtils.isMetadataFamilyFile(storagePath.getRelativePath().getFileName())) {
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

  /**
   * Parses a {@code maven-metadata.xml}. Never returns {@code null}: content that cannot be parsed
   * is refused with the unchecked {@link BadRequestException} {@code malformedMetadataFile}, so a
   * caller that wants a fallback instead has to catch that exception (RPS-1180).
   */
  public static Metadata readMetadata(final byte[] content) {

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

  private static boolean startsWithIgnoreCase(final String str, final String prefix) {
    return str.length() >= prefix.length()
        && str.regionMatches(true, 0, prefix, 0, prefix.length());
  }

  /**
   * Tells the metadata family of a file by its name alone: {@code maven-metadata.xml} itself, or
   * one of its checksums or its {@code .asc} signature ({@code maven-metadata.xml.<suffix>}),
   * matched case-insensitively. An artifactId that merely contains the literal string, such as
   * {@code maven-metadata.xml-plugin}, is not looked at: its file names start with {@code
   * maven-metadata.xml-}, not {@code maven-metadata.xml.}, the same shape of fix RPS-1196 made for
   * {@link #isPomFile} (RPS-1177).
   */
  public static boolean isMetadataFamilyFile(final String fileName) {
    return fileName.equalsIgnoreCase(METADATA_FILENAME)
        || startsWithIgnoreCase(fileName, METADATA_FILENAME + ".");
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
    return fileName.endsWith(SIGNATURE_SUFFIX)
        && isPomFile(fileName.substring(0, fileName.length() - SIGNATURE_SUFFIX.length()));
  }

  /**
   * The {@code .asc} (case-sensitive, like {@link #isMetadataSignature}) of any stored artifact
   * file: a POM, a jar, a classifier jar, a {@code .module}. The signature of a {@code
   * maven-metadata.xml} is not one, it is stored unverified (RPS-1185), and neither is the checksum
   * of a signature ({@code .asc.sha1}), which is a checksum (RPS-1183) (RPS-1188).
   */
  public static boolean isArtifactSignature(final StoragePath storagePath) {
    final var fileName = storagePath.getRelativePath().getFileName();
    return fileName.endsWith(SIGNATURE_SUFFIX) && !isMetadataFamilyFile(fileName);
  }

  /**
   * Tells whether an upload must have its signature verified before it is stored: a {@code
   * .pom.asc} always, any other artifact {@code .asc} only when the repo verifies every signature
   * (RPS-1188).
   */
  public static boolean isSignatureToVerify(
      final StoragePath storagePath, final boolean verifyAllSignatures) {
    return isPomSignature(storagePath) || (verifyAllSignatures && isArtifactSignature(storagePath));
  }

  /**
   * Tells whether a signing tool signs a file of a version directory: everything but a checksum, a
   * signature ({@code .asc}, any case) and the metadata family. That is the POM, the main artifact
   * and every attached one ({@code -sources.jar}, {@code .module}, {@code .klib}, ...), which
   * maven-gpg-plugin and Gradle's {@code signing} plugin both sign (RPS-1188).
   */
  public static boolean isSignableFile(final String fileName) {
    return !isChecksumFile(fileName)
        && !endsWithIgnoreCase(fileName, SIGNATURE_SUFFIX)
        && !isMetadataFamilyFile(fileName);
  }

  /**
   * The file names of a version directory that a version needs a verified signature for to count as
   * signed (RPS-1188): the {@linkplain #isSignableFile signable} ones. In a {@code SNAPSHOT}
   * directory only the newest build counts, the files of the highest {@code yyyyMMdd.HHmmss-N}
   * (older builds are superseded and never signed again, and a literal {@code SNAPSHOT} file counts
   * as the oldest build).
   *
   * @param versionPath the version directory, {@code <group>/<artifactId>/<version>}
   * @param fileNames the names of the files directly in it
   */
  public static List<String> filesToSign(
      final String versionPath, final Collection<String> fileNames) {

    final var signable = fileNames.stream().filter(ArtifactUtils::isSignableFile).toList();
    final var segments = versionPath.split("/", -1);
    final var version = segments[segments.length - 1];

    if (!version.endsWith(SNAPSHOT_SUFFIX) || segments.length < 2) {
      return signable;
    }

    final var stem = version.substring(0, version.length() - SNAPSHOT_SUFFIX.length());
    final var buildPattern =
        Pattern.compile(
            Pattern.quote(segments[segments.length - 2] + "-" + stem) + SNAPSHOT_BUILD_MARKER);

    return newestBuild(signable, buildPattern);
  }

  /**
   * Tells whether {@code gav} names a file of a non-unique snapshot: the literal {@code
   * <artifactId>-<version>-SNAPSHOT} name that sbt and Ivy deploy again and again, with its
   * classifier jars, checksums and signatures. A unique (timestamped) build has a timestamp, and a
   * release is not a snapshot at all. RPS-1328.
   */
  public static boolean isNonUniqueSnapshotFile(final Gav gav) {
    return gav.isSnapshot() && gav.getSnapshotTimeStamp() == null;
  }

  /**
   * The name of the main POM that a {@code SNAPSHOT} version directory holds for its newest build,
   * see {@link #newestSnapshotMainFileName}. RPS-1370.
   *
   * @param artifactId the artifactId of the version
   * @param version the {@code ...-SNAPSHOT} version, also the name of the directory
   * @param fileNames the names of the files directly in the version directory
   * @return the file name, or {@code null} if the version is not a snapshot or holds no main POM
   */
  public static @Nullable String newestSnapshotPomName(
      final String artifactId, final String version, final Collection<String> fileNames) {

    return newestSnapshotMainFileName(artifactId, version, "pom", fileNames);
  }

  /**
   * The name of the main file with the given extension that a {@code SNAPSHOT} version directory
   * holds for its newest build, as {@code <artifactId>-<version>.<extension>} would be resolved by
   * a client that could not read {@code maven-metadata.xml}: the file of the highest {@code
   * yyyyMMdd.HHmmss-N} build, or the literal {@code
   * <artifactId>-<baseVersion>-SNAPSHOT.<extension>} when no timestamped build is stored (a literal
   * file counts as the oldest build, like in {@link #filesToSign}). A file of another artifact, a
   * classifier file (for example {@code -sources.jar}), a checksum and a signature are not main
   * files. RPS-1370, RPS-1420.
   *
   * @param artifactId the artifactId of the version
   * @param version the {@code ...-SNAPSHOT} version, also the name of the directory
   * @param extension the extension of the main file, without the dot ({@code pom}, {@code jar}...)
   * @param fileNames the names of the files directly in the version directory
   * @return the file name, or {@code null} if the version is not a snapshot or holds no such file
   */
  public static @Nullable String newestSnapshotMainFileName(
      final String artifactId,
      final String version,
      final String extension,
      final Collection<String> fileNames) {

    if (!version.endsWith(SNAPSHOT_SUFFIX)) {
      return null;
    }

    final var stem = version.substring(0, version.length() - SNAPSHOT_SUFFIX.length());
    final var mainFilePattern =
        Pattern.compile(
            Pattern.quote(artifactId + "-" + stem)
                + SNAPSHOT_MAIN_FILE_MARKER
                + Pattern.quote(extension));
    String newestName = null;
    SnapshotBuild newest = null;

    for (final var name : fileNames) {
      final var matcher = mainFilePattern.matcher(name);

      if (!matcher.matches()) {
        continue;
      }

      final var build = snapshotBuildOf(matcher.group(1), matcher.group(2));

      if (newest == null || build.compareTo(newest) > 0) {
        newest = build;
        newestName = name;
      }
    }

    return newestName;
  }

  private static List<String> newestBuild(final List<String> signable, final Pattern buildPattern) {

    final Map<SnapshotBuild, List<String>> builds = new HashMap<>();

    for (final var name : signable) {
      final var matcher = buildPattern.matcher(name);

      if (matcher.lookingAt()) {
        builds
            .computeIfAbsent(
                snapshotBuildOf(matcher.group(1), matcher.group(2)), k -> new ArrayList<>())
            .add(name);
      }
    }

    return builds.entrySet().stream()
        .max(Map.Entry.comparingByKey())
        .map(Map.Entry::getValue)
        .orElse(List.of());
  }

  private static SnapshotBuild snapshotBuildOf(
      final @Nullable String timestamp, final @Nullable String buildNumber) {

    if (timestamp == null || buildNumber == null) {
      return LITERAL_SNAPSHOT;
    }

    return new SnapshotBuild(timestamp, new BigInteger(buildNumber));
  }

  /** A snapshot build, ordered by its deploy timestamp, then its build number. */
  private record SnapshotBuild(String timestamp, BigInteger number)
      implements Comparable<SnapshotBuild> {

    @Override
    public int compareTo(final SnapshotBuild other) {
      return Comparator.comparing(SnapshotBuild::timestamp)
          .thenComparing(SnapshotBuild::number)
          .compare(this, other);
    }
  }

  /**
   * The names of the files that sit directly in the version directory {@code versionPath}
   * (repo-relative, {@code <group>/<artifactId>/<version>}) among the {@code items} of a storage
   * listing. Directories are skipped, and so are the files of nested directories, which a recursive
   * listing also returns but which are not files of this version.
   */
  public static List<String> versionDirFileNames(
      final String versionPath, final List<StorageItemInfo> items) {

    return items.stream()
        .filter(item -> !item.isDirectory())
        .filter(
            item ->
                item.getPath()
                    .replace("\\", "/")
                    .endsWith("/" + versionPath + "/" + item.getName()))
        .map(StorageItemInfo::getName)
        .toList();
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
  public static boolean isPluginMetadata(final Metadata metadata) {

    return !metadata.getPlugins().isEmpty();
  }

  /**
   * Tells the version-level {@code maven-metadata.xml}, the {@code g/a/<baseVersion>/} file Maven
   * writes for snapshot deploys. It is the only metadata file that names a single version, in its
   * {@code <version>} element; the artifact-level file lists every version and the group-level file
   * lists plugins, and neither has one.
   */
  public static boolean isVersionLevelMetadata(final Metadata metadata) {

    return metadata.getVersion() != null && !metadata.getVersion().isBlank();
  }

  /**
   * Tells a checksum file by its suffix, matched case-sensitively like the M2 repository layout
   * (Maven Resolver's and the GAV calculator's own {@code .md5}/{@code .sha1}/{@code .sha256}/
   * {@code .sha512}): no real client sends an upper-case one, so {@code lib-1.0.jar.SHA1} is not a
   * checksum file, consistent with the GAV calculator, which would not strip it either (RPS-1195).
   */
  public static boolean isChecksumFile(final String fileName) {

    final var dotIndex = fileName.lastIndexOf('.');

    return dotIndex != -1 && CHECKSUM_TYPES.contains(fileName.substring(dotIndex));
  }

  /**
   * Tells the detached PGP signature of a {@code maven-metadata.xml}: {@code
   * maven-metadata.xml.asc} at any level. No official client writes one (maven-gpg-plugin, Maven
   * Resolver and Gradle sign artifacts only), but Maven Central serves them and Nexus stores them
   * as a subordinate of the metadata, like a checksum. Its body is armored text, not XML, so it is
   * never parsed and is judged by its directory like a metadata checksum (RPS-1185). A checksum of
   * it ({@code .asc.sha1}) is a checksum. Told by the file name alone, the same predicate as {@link
   * #isMetadataFamilyFile}, plus the case-sensitive {@code .asc} suffix, like {@link
   * #isPomSignature} (RPS-1177).
   */
  public static boolean isMetadataSignature(final String fileName) {

    return isMetadataFamilyFile(fileName) && fileName.endsWith(SIGNATURE_SUFFIX);
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

  /**
   * Tells whether a file's GAV can be read straight from its path: any file outside the metadata
   * family ({@link #isMetadataFamilyFile}), which is classified from its content instead
   * (RPS-1177).
   */
  public static boolean isFileSuitableForGavExtraction(final String fileName) {

    return !ArtifactUtils.isMetadataFamilyFile(fileName);
  }
}
