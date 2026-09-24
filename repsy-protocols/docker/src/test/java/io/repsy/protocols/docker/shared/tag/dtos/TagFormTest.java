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
package io.repsy.protocols.docker.shared.tag.dtos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("TagForm")
class TagFormTest {

  private static TagForm formFor(final String reference) {
    return TagForm.builder().tag(reference).platform("linux/amd64").mediaType("m").build();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"latest", "v1.2.3", "sha", "sha256", "sha512-tag", "my_tag-1"})
  @DisplayName("a reference that is not a digest is a tag")
  void tagReferences(final String reference) {
    assertThat(formFor(reference).isTagReference()).isTrue();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "sha256:0000000000000000000000000000000000000000000000000000000000000000",
        "sha512:00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
      })
  @DisplayName("a sha256 or sha512 digest reference is not a tag, so a push by it creates no tag")
  void digestReferencesAreNotTags(final String reference) {
    assertThat(formFor(reference).isTagReference()).isFalse();
  }

  @Test
  @DisplayName("the media type of an index tag is the index's own, of an image tag the pushed one")
  void calculatedMediaType() {
    final var list = new ManifestList();
    list.setMediaType("application/vnd.oci.image.index.v1+json");
    list.setManifests(List.of());
    final var index =
        TagForm.builder()
            .tag("t")
            .platform("Multiplatform")
            .mediaType("x")
            .manifestList(list)
            .build();

    assertThat(index.getCalculatedMediaType()).isEqualTo("application/vnd.oci.image.index.v1+json");
    assertThat(formFor("t").getCalculatedMediaType()).isEqualTo("m");
  }
}
