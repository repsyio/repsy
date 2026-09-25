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

  boolean existsByIdAndRepoId(UUID id, UUID repoId);

  boolean existsByRepoIdAndDigest(UUID repoId, String digest);

  long countByRepoIdAndDigestIn(UUID repoId, List<String> digests);

  @Query(
      """
    select l from Layer l
    where l.repo.id = :repoId
    and l.manifests is empty
    """)
  List<Layer> findOrphansByRepoId(UUID repoId);

  /**
   * The size of the distinct layers of the manifests the image's tags reach: the manifests a tag
   * points at and the manifests of the indexes among them, however deep an index lists another (the
   * recursive CTE follows the edges to the end, as {@code UntaggedManifestFinder} does). A manifest
   * no tag reaches any more (the one a tag was moved away from) stays on disk but is not part of
   * what the image shows.
   *
   * <p>The identifiers are quoted and schema-qualified, as in {@code insertIfAbsent}: the H2
   * migrations create lower-case quoted names, which H2 matches case-sensitively, so a bare {@code
   * docker_tag} is {@code DOCKER_TAG} there and is not found (RPS-1385).
   */
  @Query(
      value =
          """
          with recursive reach(manifest_id) as (
            select t."manifest_id" from "public"."docker_tag" t where t."image_id" = :imageId
            union
            select c."child_id" from "public"."docker_manifest_child" c
              join reach r on c."parent_id" = r.manifest_id
          )
          select cast(coalesce(sum(l."size"), 0) as bigint) from "public"."docker_layer" l
          where l."repo_id" = :repoId
            and l."id" in (
              select ml."layer_id" from "public"."docker_manifest_layer" ml
              where ml."manifest_id" in (select manifest_id from reach)
            )
          """,
      nativeQuery = true)
  long sumDistinctSizeByImageId(UUID repoId, UUID imageId);
}
