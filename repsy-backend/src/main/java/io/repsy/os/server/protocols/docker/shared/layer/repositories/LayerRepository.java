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
package io.repsy.os.server.protocols.docker.shared.layer.repositories;

import io.repsy.os.server.protocols.docker.shared.layer.entities.Layer;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
@NullMarked
public interface LayerRepository extends JpaRepository<Layer, UUID> {

  Set<Layer> findAllByRepoIdAndDigestIn(UUID repoId, List<String> digests);

  List<Layer> findAllByRepoId(UUID repoId);

  @Query(
      """
        select layer from Layer layer
          join layer.repo repo
        where repo.id = :repoId
          and layer.digest = :digest
          order by layer.createdAt desc
      """)
  Optional<Layer> findByRepoIdAndDigest(UUID repoId, String digest);

  long countByRepoIdAndDigestIn(UUID repoId, List<String> digests);

  @Query(
      """
    select coalesce(sum(l.size), 0) from Layer l
      where l.id in (
        select distinct l2.id from Layer l2
          join l2.manifests m
          join m.tagPlatform tp
          join tp.tag t
        where t.image.id = :imageId and l2.repo.id = :repoId
      )
  """)
  long sumDistinctSizeByImageId(UUID repoId, UUID imageId);
}
