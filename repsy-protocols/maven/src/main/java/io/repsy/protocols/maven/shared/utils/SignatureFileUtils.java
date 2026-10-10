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
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;

@UtilityClass
@NullMarked
public class SignatureFileUtils {

  private static final String SIGNATURE_SUFFIX = ".asc";

  /**
   * The {@code .asc} (case-sensitive, like {@link #isMetadataSignature}) of a file that {@link
   * MavenFileNameUtils#isPomFile}.
   */
  public static boolean isPomSignature(final StoragePath storagePath) {
    final var fileName = storagePath.getRelativePath().getFileName();
    return fileName.endsWith(SIGNATURE_SUFFIX)
        && MavenFileNameUtils.isPomFile(
            fileName.substring(0, fileName.length() - SIGNATURE_SUFFIX.length()));
  }

  /**
   * The {@code .asc} (case-sensitive, like {@link #isMetadataSignature}) of any stored artifact
   * file: a POM, a jar, a classifier jar, a {@code .module}. The signature of a {@code
   * maven-metadata.xml} is not one, it is stored unverified (RPS-1185), and neither is the checksum
   * of a signature ({@code .asc.sha1}), which is a checksum (RPS-1183) (RPS-1188).
   */
  public static boolean isArtifactSignature(final StoragePath storagePath) {
    final var fileName = storagePath.getRelativePath().getFileName();
    return fileName.endsWith(SIGNATURE_SUFFIX)
        && !MavenFileNameUtils.isMetadataFamilyFile(fileName);
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
    return !MavenFileNameUtils.isChecksumFile(fileName)
        && !MavenFileNameUtils.endsWithIgnoreCase(fileName, SIGNATURE_SUFFIX)
        && !MavenFileNameUtils.isMetadataFamilyFile(fileName);
  }

  /**
   * Tells the detached PGP signature of a {@code maven-metadata.xml}: {@code
   * maven-metadata.xml.asc} at any level. No official client writes one (maven-gpg-plugin, Maven
   * Resolver and Gradle sign artifacts only), but Maven Central serves them and Nexus stores them
   * as a subordinate of the metadata, like a checksum. Its body is armored text, not XML, so it is
   * never parsed and is judged by its directory like a metadata checksum (RPS-1185). A checksum of
   * it ({@code .asc.sha1}) is a checksum. Told by the file name alone, the same predicate as {@link
   * MavenFileNameUtils#isMetadataFamilyFile}, plus the case-sensitive {@code .asc} suffix, like
   * {@link #isPomSignature} (RPS-1177).
   */
  public static boolean isMetadataSignature(final String fileName) {

    return MavenFileNameUtils.isMetadataFamilyFile(fileName) && fileName.endsWith(SIGNATURE_SUFFIX);
  }
}
