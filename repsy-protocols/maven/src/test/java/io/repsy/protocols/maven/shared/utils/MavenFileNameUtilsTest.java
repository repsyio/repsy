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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("MavenFileNameUtils")
class MavenFileNameUtilsTest {

  @ParameterizedTest(name = "{0} is metadata-family: {1}")
  @CsvSource({
    "maven-metadata.xml, true",
    "MAVEN-METADATA.XML, true",
    "maven-metadata.xml.sha1, true",
    "maven-metadata.xml.SHA1, true",
    "maven-metadata.xml.md5, true",
    "maven-metadata.xml.asc, true",
    "maven-metadata.xml.asc.sha1, true",
    "lib-1.0.jar, false",
    "maven-metadata.xml-plugin-1.0.jar, false",
    "maven-metadata.xml-plugin-1.0.jar.sha1, false",
    "my-maven-metadata.xml, false"
  })
  @DisplayName(
      "tells the metadata family by the file name alone, not a substring of it (RPS-1177, same"
          + " shape of fix as RPS-1196)")
  void recognisesTheMetadataFamilyByFileNameAlone(final String fileName, final boolean expected) {
    assertThat(MavenFileNameUtils.isMetadataFamilyFile(fileName)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0} is a checksum file: {1}")
  @CsvSource({
    "lib-1.0.jar.sha1, true",
    "lib-1.0.jar.md5, true",
    "lib-1.0.jar.sha256, true",
    "lib-1.0.jar.sha512, true",
    "lib-1.0.jar.SHA1, false",
    "lib-1.0.jar.Md5, false",
    "lib-1.0.jar, false",
    "maven-metadata.xml.sha1, true",
    "maven-metadata.xml.SHA1, false"
  })
  @DisplayName(
      "tells a checksum file by its suffix, case-sensitively like the M2 layout (RPS-1195)")
  void checksumSuffixIsMatchedCaseSensitively(final String fileName, final boolean expected) {
    assertThat(MavenFileNameUtils.isChecksumFile(fileName)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0} is a POM file: {1}")
  @CsvSource({
    "lib-1.0.pom, true",
    "lib-1.0.POM, true",
    "lib-1.0.pom.asc, false",
    "lib-1.0.pom.sha1, false",
    "lib-1.0.jar, false",
    "pom, false"
  })
  @DisplayName("tells a POM by its file name alone, any case (RPS-1196)")
  void recognisesAPomFile(final String fileName, final boolean expected) {
    assertThat(MavenFileNameUtils.isPomFile(fileName)).isEqualTo(expected);
  }
}
