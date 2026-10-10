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

import java.util.Set;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;

@UtilityClass
@NullMarked
public class MavenFileNameUtils {

  private static final String METADATA_FILENAME = "maven-metadata.xml";

  private static final String POM_SUFFIX = ".pom";

  private static final Set<String> CHECKSUM_TYPES = Set.of(".md5", ".sha1", ".sha256", ".sha512");

  public static boolean endsWithIgnoreCase(final String str, final String suffix) {
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
}
