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
package io.repsy.protocols.maven.shared.artifact.dtos;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A request for the literal {@code <artifactId>-<base>-SNAPSHOT.<extension>} file of a {@code
 * SNAPSHOT} directory, or for one of its checksums (RPS-1958).
 *
 * @param directory the version directory, {@code <group>/<artifactId>/<version>}
 * @param artifactId the artifactId of the version
 * @param version the {@code ...-SNAPSHOT} version, also the name of the directory
 * @param mainName the file name without a checksum suffix
 * @param checksumSuffix the checksum suffix of the file name, {@code .sha1} for example, or empty
 * @param extension the extension of the main file, without the dot
 */
public record LiteralSnapshotRequest(
    String directory,
    String artifactId,
    String version,
    String mainName,
    String checksumSuffix,
    String extension) {

  private static final int PATH_SEGMENTS = 3;
  private static final List<String> CHECKSUM_SUFFIXES =
      List.of(".sha1", ".md5", ".sha256", ".sha512");

  /** The request a path is, or {@code null} when it is not a literal SNAPSHOT file path. */
  public static @Nullable LiteralSnapshotRequest parse(final String path) {

    final var segments = path.split("/", -1);

    if (segments.length < PATH_SEGMENTS) {
      return null;
    }

    final var fileName = segments[segments.length - 1];
    final var version = segments[segments.length - 2];
    final var artifactId = segments[segments.length - PATH_SEGMENTS];
    final var checksumSuffix = checksumSuffixOf(fileName);
    final var mainName = fileName.substring(0, fileName.length() - checksumSuffix.length());
    final var prefix = artifactId + "-" + version + ".";

    if (!version.endsWith("-SNAPSHOT")
        || !mainName.startsWith(prefix)
        || mainName.length() == prefix.length()) {
      return null;
    }

    return new LiteralSnapshotRequest(
        path.substring(0, path.length() - fileName.length() - 1),
        artifactId,
        version,
        mainName,
        checksumSuffix,
        mainName.substring(prefix.length()));
  }

  private static String checksumSuffixOf(final String fileName) {

    return CHECKSUM_SUFFIXES.stream().filter(fileName::endsWith).findFirst().orElse("");
  }
}
