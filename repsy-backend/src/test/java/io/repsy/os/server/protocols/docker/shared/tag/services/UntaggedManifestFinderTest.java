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
package io.repsy.os.server.protocols.docker.shared.tag.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.repsy.os.server.protocols.docker.shared.tag.entities.Manifest;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestChildRepository;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestEdgeView;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestRepository;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.TagRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UntaggedManifestFinder")
class UntaggedManifestFinderTest {

  private static final UUID IMAGE = UUID.randomUUID();

  private final ManifestRepository manifestRepository = mock(ManifestRepository.class);
  private final TagRepository tagRepository = mock(TagRepository.class);
  private final ManifestChildRepository manifestChildRepository =
      mock(ManifestChildRepository.class);
  private final UntaggedManifestFinder finder =
      new UntaggedManifestFinder(
          this.manifestRepository, this.tagRepository, this.manifestChildRepository);

  private static UUID id() {
    return UUID.randomUUID();
  }

  private static Manifest manifest(final UUID id) {
    final var manifest = new Manifest();
    manifest.setId(id);

    return manifest;
  }

  private static ManifestEdgeView edge(final UUID parent, final UUID child) {
    final var edge = mock(ManifestEdgeView.class);
    when(edge.getParentId()).thenReturn(parent);
    when(edge.getChildId()).thenReturn(child);

    return edge;
  }

  @Test
  @DisplayName("a manifest no tag points to is untagged, a tagged one is not")
  void aTaggedManifestIsNotUntagged() {
    final var tagged = id();
    final var untagged = id();
    when(this.manifestRepository.findAllByImageId(IMAGE))
        .thenReturn(List.of(manifest(tagged), manifest(untagged)));
    when(this.tagRepository.findManifestIdsByImageId(IMAGE)).thenReturn(List.of(tagged));
    when(this.manifestChildRepository.findEdgesByImageId(IMAGE)).thenReturn(List.of());

    assertThat(this.finder.findUntagged(IMAGE))
        .extracting(Manifest::getId)
        .containsExactly(untagged);
  }

  @Test
  @DisplayName("the children of a tagged index are reachable, those of an untagged index are not")
  void childrenAreReachableOnlyThroughATaggedIndex() {
    final var taggedIndex = id();
    final var reachableChild = id();
    final var untaggedIndex = id();
    final var unreachableChild = id();
    final var sharedChild = id();
    when(this.manifestRepository.findAllByImageId(IMAGE))
        .thenReturn(
            List.of(
                manifest(taggedIndex),
                manifest(reachableChild),
                manifest(untaggedIndex),
                manifest(unreachableChild),
                manifest(sharedChild)));
    when(this.tagRepository.findManifestIdsByImageId(IMAGE)).thenReturn(List.of(taggedIndex));
    final var edges =
        List.of(
            edge(taggedIndex, reachableChild),
            edge(taggedIndex, sharedChild),
            edge(untaggedIndex, unreachableChild),
            edge(untaggedIndex, sharedChild));
    when(this.manifestChildRepository.findEdgesByImageId(IMAGE)).thenReturn(edges);

    assertThat(this.finder.findUntagged(IMAGE))
        .extracting(Manifest::getId)
        .containsExactlyInAnyOrder(untaggedIndex, unreachableChild);
  }

  @Test
  @DisplayName("nothing is untagged when the image has no manifest")
  void anImageWithoutManifestsHasNothingUntagged() {
    when(this.manifestRepository.findAllByImageId(IMAGE)).thenReturn(List.of());
    when(this.tagRepository.findManifestIdsByImageId(IMAGE)).thenReturn(List.of());
    when(this.manifestChildRepository.findEdgesByImageId(IMAGE)).thenReturn(List.of());

    assertThat(this.finder.findUntagged(IMAGE)).isEmpty();
  }

  @Test
  @DisplayName("reachability follows nested indexes to a fixpoint")
  void reachabilityFollowsNestedIndexes() {
    final var top = id();
    final var middle = id();
    final var bottom = id();
    final var leaf = id();
    final var elsewhere = id();

    final var reached =
        UntaggedManifestFinder.reachable(
            List.of(top),
            Map.of(top, List.of(middle), middle, List.of(bottom), bottom, List.of(leaf)));

    assertThat(reached).containsExactlyInAnyOrder(top, middle, bottom, leaf);
    assertThat(reached).doesNotContain(elsewhere);
  }

  @Test
  @DisplayName("reachability terminates on a cycle of indexes")
  void reachabilityTerminatesOnACycle() {
    final var first = id();
    final var second = id();

    final var reached =
        UntaggedManifestFinder.reachable(
            List.of(first), Map.of(first, List.of(second), second, List.of(first)));

    assertThat(reached).containsExactlyInAnyOrder(first, second);
  }
}
