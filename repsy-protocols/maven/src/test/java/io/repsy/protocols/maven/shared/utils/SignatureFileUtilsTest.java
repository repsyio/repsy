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

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.libs.storage.core.dtos.StoragePath;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("SignatureFileUtils")
class SignatureFileUtilsTest {

  @ParameterizedTest(name = "{0} is a POM signature: {1}")
  @CsvSource({
    "com/acme/lib/1.0/lib-1.0.pom.asc, true",
    "LIB-1.0.POM.asc, true",
    "lib-1.0.pom.ASC, false",
    "lib-1.0.jar.asc, false",
    "lib-1.0.pom.asc.sha1, false",
    "com/acme/bar.pom.utils/1.0/bar.pom.utils-1.0.jar.asc, false",
    "com/acme/bar.pom.utils/1.0/bar.pom.utils-1.0.pom.asc, true",
    "com/acme/bar.pom.utils/maven-metadata.xml.asc, false",
    "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1.pom.asc, true"
  })
  @DisplayName("tells the .asc signature of a POM by its file name alone (RPS-1196)")
  void recognisesAPomSignature(final String path, final boolean expected) {
    final var storagePath = StoragePath.of(UUID.randomUUID(), path);

    assertThat(SignatureFileUtils.isPomSignature(storagePath)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0} is a metadata signature: {1}")
  @CsvSource({
    "maven-metadata.xml.asc, true",
    "MAVEN-METADATA.XML.asc, true",
    "maven-metadata.xml.asc.sha1, false",
    "maven-metadata.xml, false",
    "lib-1.0.pom.asc, false",
    "lib-1.0.jar.asc, false",
    "maven-metadata.xml.ASC, false",
    "maven-metadata.xml-plugin-1.0.jar.asc, false"
  })
  @DisplayName(
      "tells the .asc signature of a maven-metadata.xml by its file name alone, at any level"
          + " (RPS-1185, RPS-1177)")
  void recognisesAMetadataSignature(final String fileName, final boolean expected) {
    assertThat(SignatureFileUtils.isMetadataSignature(fileName)).isEqualTo(expected);
  }

  @Test
  @DisplayName("a metadata signature is neither a POM signature nor a POM to parse (RPS-1185)")
  void aMetadataSignatureIsNotAPomSignature() {
    final var storagePath =
        StoragePath.of(UUID.randomUUID(), "com/acme/lib/maven-metadata.xml.asc");

    assertThat(SignatureFileUtils.isPomSignature(storagePath)).isFalse();
    assertThat(PomModelUtils.isPomToParse(storagePath)).isFalse();
  }

  @ParameterizedTest(name = "{0} is an artifact signature: {1}")
  @CsvSource({
    "com/acme/lib/1.0/lib-1.0.pom.asc, true",
    "com/acme/lib/1.0/lib-1.0.jar.asc, true",
    "com/acme/lib/1.0/lib-1.0-sources.jar.asc, true",
    "com/acme/lib/1.0/lib-1.0.module.asc, true",
    "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1.jar.asc, true",
    "com/acme/lib/1.0/lib-1.0.jar.asc.sha1, false",
    "com/acme/lib/1.0/lib-1.0.jar.ASC, false",
    "com/acme/lib/maven-metadata.xml.asc, false",
    "com/acme/lib/1.0-SNAPSHOT/maven-metadata.xml.asc, false",
    "com/acme/lib/1.0/lib-1.0.jar, false"
  })
  @DisplayName("tells the .asc signature of any artifact file by its file name alone (RPS-1188)")
  void recognisesAnArtifactSignature(final String path, final boolean expected) {
    final var storagePath = StoragePath.of(UUID.randomUUID(), path);

    assertThat(SignatureFileUtils.isArtifactSignature(storagePath)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}, verify all {1}: {2}")
  @CsvSource({
    "com/acme/lib/1.0/lib-1.0.pom.asc, false, true",
    "com/acme/lib/1.0/lib-1.0.pom.asc, true, true",
    "com/acme/lib/1.0/lib-1.0.jar.asc, false, false",
    "com/acme/lib/1.0/lib-1.0.jar.asc, true, true",
    "com/acme/lib/maven-metadata.xml.asc, true, false",
    "com/acme/lib/1.0/lib-1.0.jar.asc.sha1, true, false",
    "com/acme/lib/1.0/lib-1.0.jar, true, false"
  })
  @DisplayName("a POM signature is always verified, any other one only when verifying all")
  void tellsWhichSignaturesAreVerified(
      final String path, final boolean verifyAll, final boolean expected) {
    final var storagePath = StoragePath.of(UUID.randomUUID(), path);

    assertThat(SignatureFileUtils.isSignatureToVerify(storagePath, verifyAll)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0} is signable: {1}")
  @CsvSource({
    "lib-1.0.pom, true",
    "lib-1.0.jar, true",
    "lib-1.0-sources.jar, true",
    "lib-1.0-javadoc.jar, true",
    "lib-1.0.module, true",
    "lib-1.0.klib, true",
    "lib-1.0.tar.gz, true",
    "lib-1.0-kotlin-tooling-metadata.json, true",
    "lib-1.0.jar.asc, false",
    "lib-1.0.jar.ASC, false",
    "lib-1.0.jar.sha1, false",
    "lib-1.0.jar.md5, false",
    "lib-1.0.jar.sha256, false",
    "lib-1.0.jar.sha512, false",
    "lib-1.0.jar.asc.sha1, false",
    "maven-metadata.xml, false",
    "maven-metadata.xml.sha1, false",
    "maven-metadata.xml.asc, false"
  })
  @DisplayName("tells the files a signing tool signs: not a checksum, a signature or metadata")
  void recognisesASignableFile(final String fileName, final boolean expected) {
    assertThat(SignatureFileUtils.isSignableFile(fileName)).isEqualTo(expected);
  }
}
