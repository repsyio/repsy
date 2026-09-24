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

import io.repsy.os.server.protocols.docker.shared.tag.entities.Manifest;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestChildRepository;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestEdgeView;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestRepository;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.TagRepository;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds the manifests of an image that nothing reaches: no tag points at them, directly or through
 * the index a tag points at (an index may list another index, so the walk runs to a fixpoint).
 *
 * <p>"Untagged" is computed when the cleanup runs, not recorded by the push path. A manifest pushed
 * by digest whose tag or index arrives a moment later is untagged in between, which is why the
 * cleanup is a manual action.
 */
@Component
@RequiredArgsConstructor
@NullMarked
public class UntaggedManifestFinder {

  private final ManifestRepository manifestRepository;
  private final TagRepository tagRepository;
  private final ManifestChildRepository manifestChildRepository;

  @Transactional(readOnly = true)
  public List<Manifest> findUntagged(final UUID imageId) {

    final var edges = this.manifestChildRepository.findEdgesByImageId(imageId);
    final var reachable =
        reachable(this.tagRepository.findManifestIdsByImageId(imageId), childrenByParent(edges));

    return this.manifestRepository.findAllByImageId(imageId).stream()
        .filter(manifest -> !reachable.contains(manifest.getId()))
        .toList();
  }

  /**
   * The manifests reachable from the tagged ones.
   *
   * @param tagged The manifests some tag points at
   * @param children For an index, the manifests it lists
   */
  static Set<UUID> reachable(final Collection<UUID> tagged, final Map<UUID, List<UUID>> children) {

    final var reached = new HashSet<>(tagged);
    final var pending = new ArrayDeque<>(tagged);

    while (!pending.isEmpty()) {
      for (final var child : children.getOrDefault(pending.poll(), List.of())) {
        if (reached.add(child)) {
          pending.add(child);
        }
      }
    }

    return reached;
  }

  private static Map<UUID, List<UUID>> childrenByParent(final List<ManifestEdgeView> edges) {

    final var children = new HashMap<UUID, List<UUID>>();

    for (final var edge : edges) {
      children
          .computeIfAbsent(edge.getParentId(), parent -> new ArrayList<>())
          .add(edge.getChildId());
    }

    return children;
  }
}
