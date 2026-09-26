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
package io.repsy.scanner.trivy.services;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.scanner.trivy.dtos.AdvisoryPackage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class CycloneDxSbomWriterTest {

  @TempDir Path tempDir;

  @Test
  void writesACycloneDx15DocumentWithOneLibraryPerPair() throws IOException {
    final var file = this.tempDir.resolve("sbom.json");

    new CycloneDxSbomWriter(JsonMapper.builder().build())
        .writeNpm(
            file,
            List.of(
                new AdvisoryPackage("lodash", "4.17.20"),
                new AdvisoryPackage("@babel/traverse", "7.20.0")));

    final var tree = JsonMapper.builder().build().readTree(Files.readString(file));
    assertThat(tree.get("bomFormat").asString()).isEqualTo("CycloneDX");
    assertThat(tree.get("specVersion").asString()).isEqualTo("1.5");
    assertThat(tree.get("components")).hasSize(2);
    assertThat(tree.get("components").get(0).get("type").asString()).isEqualTo("library");
    assertThat(tree.get("components").get(0).get("purl").asString())
        .isEqualTo("pkg:npm/lodash@4.17.20");
    assertThat(tree.get("components").get(1).get("purl").asString())
        .isEqualTo("pkg:npm/%40babel/traverse@7.20.0");
  }

  @Test
  void encodesTheScopeOfAScopedPackageAsANamespace() {
    assertThat(CycloneDxSbomWriter.npmPurl("@types/node", "20.1.0"))
        .isEqualTo("pkg:npm/%40types/node@20.1.0");
  }

  @Test
  void keepsTwoVersionsOfOnePackageAsTwoComponentsWithUniqueRefs() {
    final var bom =
        CycloneDxSbomWriter.buildNpm(
            List.of(new AdvisoryPackage("ms", "2.0.0"), new AdvisoryPackage("ms", "2.1.3")));

    assertThat(bom.components())
        .extracting(CycloneDxSbomWriter.BomComponent::purl)
        .containsExactly("pkg:npm/ms@2.0.0", "pkg:npm/ms@2.1.3");
    assertThat(bom.components())
        .extracting(CycloneDxSbomWriter.BomComponent::bomRef)
        .doesNotHaveDuplicates();
  }

  @Test
  void percentEncodesWhatCouldChangeTheMeaningOfTheUrl() {
    // "+" starts a build tag; "?", "#", "@" and "/" are URL syntax; a space and non-ASCII are not
    // allowed as they are.
    assertThat(CycloneDxSbomWriter.npmPurl("a", "1.0.0+build.5"))
        .isEqualTo("pkg:npm/a@1.0.0%2Bbuild.5");
    assertThat(CycloneDxSbomWriter.npmPurl("a", "1?x#y@z/w"))
        .isEqualTo("pkg:npm/a@1%3Fx%23y%40z%2Fw");
    assertThat(CycloneDxSbomWriter.npmPurl("a b", "1.0.0")).isEqualTo("pkg:npm/a%20b@1.0.0");
    assertThat(CycloneDxSbomWriter.npmPurl("café", "1.0.0")).isEqualTo("pkg:npm/caf%C3%A9@1.0.0");
  }

  @Test
  void keepsUnreservedCharactersAsTheyAre() {
    assertThat(CycloneDxSbomWriter.npmPurl("a-b_c.d~e", "1.0.0-rc.1"))
        .isEqualTo("pkg:npm/a-b_c.d~e@1.0.0-rc.1");
  }

  @Test
  void jsonEscapesQuotesInTheNameInsteadOfBreakingTheDocument() throws IOException {
    final var file = this.tempDir.resolve("sbom.json");

    new CycloneDxSbomWriter(JsonMapper.builder().build())
        .writeNpm(file, List.of(new AdvisoryPackage("a\"b\\c", "1.0.0")));

    final var tree = JsonMapper.builder().build().readTree(Files.readString(file));
    assertThat(tree.get("components").get(0).get("name").asString()).isEqualTo("a\"b\\c");
    assertThat(tree.get("components").get(0).get("purl").asString())
        .isEqualTo("pkg:npm/a%22b%5Cc@1.0.0");
  }
}
