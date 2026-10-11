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

import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

@UtilityClass
public class SnapshotNameUtils {

  static final String SNAPSHOT_SUFFIX = "SNAPSHOT";

  private static final String SNAPSHOT_BUILD_MARKER = "(?:SNAPSHOT|(\\d{8}\\.\\d{6})-(\\d+))[.-]";

  private static final String SNAPSHOT_MAIN_FILE_MARKER =
      "(?:SNAPSHOT|(\\d{8}\\.\\d{6})-(\\d+))\\.";

  private static final SnapshotBuild LITERAL_SNAPSHOT = new SnapshotBuild("", BigInteger.ZERO);

  public static boolean isSnapshot(final String versionName) {

    return org.apache.maven.artifact.ArtifactUtils.isSnapshot(versionName);
  }

  /**
   * The file names of a version directory that a version needs a verified signature for to count as
   * signed (RPS-1188): the {@linkplain SignatureFileUtils#isSignableFile signable} ones. In a
   * {@code SNAPSHOT} directory only the newest build counts, the files of the highest {@code
   * yyyyMMdd.HHmmss-N} (older builds are superseded and never signed again, and a literal {@code
   * SNAPSHOT} file counts as the oldest build).
   *
   * @param versionPath the version directory, {@code <group>/<artifactId>/<version>}
   * @param fileNames the names of the files directly in it
   */
  public static List<String> filesToSign(
      final String versionPath, final Collection<String> fileNames) {

    final var signable = fileNames.stream().filter(SignatureFileUtils::isSignableFile).toList();
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
}
