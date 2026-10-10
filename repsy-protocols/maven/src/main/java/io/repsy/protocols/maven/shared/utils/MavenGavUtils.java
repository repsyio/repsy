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

import io.repsy.libs.storage.core.dtos.StoragePath;
import java.util.Arrays;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.index.artifact.Gav;
import org.apache.maven.index.artifact.M2GavCalculator;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@Slf4j
@UtilityClass
@NullMarked
public class MavenGavUtils {

  private static final String SNAPSHOT_MARKER = "(SNAPSHOT|\\d{8}\\.\\d{6}-\\d+)[.-]";

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
   * @param path A path a metadata-family file name ({@link
   *     MavenFileNameUtils#isMetadataFamilyFile}) was found at
   */
  @Nullable
  private static Gav convertPathToBasicGav(final String path) {

    // group (>=1 segment) + artifactId + version, ahead of the file itself.
    final var minSegmentsBeforeFile = 3;

    final var s = path.startsWith("/") ? path.substring(1) : path;
    final var segments = s.split("/", -1);
    final var fileIndex = segments.length - 1;

    if (fileIndex < minSegmentsBeforeFile
        || !segments[fileIndex - 1].endsWith(SnapshotNameUtils.SNAPSHOT_SUFFIX)) {
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

    if (!version.endsWith(SnapshotNameUtils.SNAPSHOT_SUFFIX)) {
      return true;
    }

    final var stem =
        version.substring(0, version.length() - SnapshotNameUtils.SNAPSHOT_SUFFIX.length());

    return Pattern.compile(Pattern.quote(artifactId + "-" + stem) + SNAPSHOT_MARKER)
        .matcher(fileName)
        .lookingAt();
  }

  public static @Nullable Gav getGavByFile(final StoragePath storagePath) {

    if (MavenFileNameUtils.isMetadataFamilyFile(storagePath.getRelativePath().getFileName())) {
      return MavenGavUtils.convertPathToBasicGav(storagePath.getRelativePath().getPath());
    } else {
      return MavenGavUtils.convertPathToGav(storagePath.getRelativePath().getPath());
    }
  }

  /**
   * The jar of a version itself, {@code <artifactId>-<version>.jar}: no classifier, and neither a
   * checksum nor a signature of it. Its POM has the same name, so it is the file whose {@code
   * plugin.xml} tells the goal prefix of a plugin (RPS-1589).
   */
  public static boolean isMainJar(final StoragePath storagePath) {

    final var gav = convertPathToGav(storagePath.getRelativePath().getPath());

    return gav != null
        && !gav.isHash()
        && !gav.isSignature()
        && gav.getClassifier() == null
        && "jar".equals(gav.getExtension());
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
   * Tells whether a file sits in a {@code SNAPSHOT} version directory: the second-to-last segment
   * of the path ends with {@code SNAPSHOT}, the same rule as {@code isSnapshotFileOfItsDirectory}
   * and the GAV calculator's. It is how a version-level {@code maven-metadata.xml} checksum is told
   * from the artifact-level and group-level ones, as its body is a hash and holds no {@code
   * <version>} (RPS-1183).
   */
  public static boolean isSnapshotVersionDirectoryFile(final String path) {

    final var segments = path.split("/", -1);
    final var directoryIndex = segments.length - 2;

    return directoryIndex >= 0
        && segments[directoryIndex].endsWith(SnapshotNameUtils.SNAPSHOT_SUFFIX);
  }

  /**
   * Tells whether a file's GAV can be read straight from its path: any file outside the metadata
   * family ({@link MavenFileNameUtils#isMetadataFamilyFile}), which is classified from its content
   * instead (RPS-1177).
   */
  public static boolean isFileSuitableForGavExtraction(final String fileName) {

    return !MavenFileNameUtils.isMetadataFamilyFile(fileName);
  }
}
