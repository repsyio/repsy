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
package io.repsy.os.server.protocols.docker.shared.layer.entities;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LayerTest {

  private static final String DIGEST = "sha256:aaaa";

  private static Layer layer(final UUID id, final String digest) {
    final var layer = new Layer();
    layer.setId(id);
    layer.setDigest(digest);
    return layer;
  }

  @Test
  @DisplayName("Layers with the same id on distinct UUID instances are equal and hash alike")
  void sameIdOnDistinctUuidInstancesIsEqual() {
    final var uuid = UUID.randomUUID();
    final var first = layer(uuid, DIGEST);
    final var second =
        layer(new UUID(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()), DIGEST);

    assertThat(first.getId()).isNotSameAs(second.getId());
    assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
  }

  @Test
  @DisplayName("Layers with different ids are not equal, even with the same digest")
  void differentIdsAreNotEqual() {
    assertThat(layer(UUID.randomUUID(), DIGEST)).isNotEqualTo(layer(UUID.randomUUID(), DIGEST));
  }

  @Test
  @DisplayName("Equality follows the id, not the digest")
  void equalityIgnoresDigest() {
    final var id = UUID.randomUUID();

    assertThat(layer(id, DIGEST)).isEqualTo(layer(id, "sha256:bbbb"));
  }

  @Test
  @DisplayName("Layers without an id equal only themselves")
  void transientLayersEqualOnlyThemselves() {
    final var layer = layer(null, DIGEST);

    assertThat(layer).isEqualTo(layer);
    assertThat(layer).isNotEqualTo(layer(null, DIGEST));
  }

  @Test
  @DisplayName("A layer is never equal to null or another type")
  void notEqualToNullOrOtherType() {
    final var layer = layer(UUID.randomUUID(), DIGEST);

    assertThat(layer).isNotEqualTo(null).isNotEqualTo(DIGEST);
  }

  @Test
  @DisplayName("A transient layer stays findable in a HashSet after its id is assigned")
  void transientLayerStaysFindableAfterIdAssigned() {
    final var layer = layer(null, DIGEST);
    final var layers = new HashSet<Layer>();
    layers.add(layer);

    layer.setId(UUID.randomUUID());

    assertThat(layers).contains(layer);
    assertThat(layers.remove(layer)).isTrue();
  }

  @Test
  @DisplayName("A HashSet de-duplicates layers loaded as separate instances of the same row")
  void hashSetDeduplicatesSameRow() {
    final var id = UUID.randomUUID();
    final var layers = new HashSet<Layer>();

    layers.add(layer(id, DIGEST));
    layers.add(layer(new UUID(id.getMostSignificantBits(), id.getLeastSignificantBits()), DIGEST));
    layers.add(layer(UUID.randomUUID(), "sha256:bbbb"));

    assertThat(layers).hasSize(2);
  }

  @Test
  @DisplayName("Printing a layer never touches its lazy manifests or repo")
  void toStringSkipsAssociations() {
    final var layer = layer(UUID.randomUUID(), DIGEST);
    layer.setManifests(
        new HashSet<>() {
          private static final long serialVersionUID = 1L;

          @Override
          public String toString() {
            throw new IllegalStateException("lazy collection was printed");
          }
        });

    assertThat(layer.toString()).contains(DIGEST).doesNotContain("manifests=", "repo=");
  }
}
