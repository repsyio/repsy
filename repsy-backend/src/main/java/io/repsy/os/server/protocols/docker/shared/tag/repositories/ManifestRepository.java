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
package io.repsy.os.server.protocols.docker.shared.tag.repositories;

import io.repsy.os.server.protocols.docker.shared.layer.dtos.ManifestListItem;
import io.repsy.os.server.protocols.docker.shared.tag.entities.Manifest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
@NullMarked
public interface ManifestRepository extends JpaRepository<Manifest, UUID> {

  boolean existsByTagPlatformTagImageIdAndConfigDigest(UUID imageId, String configDigest);

  /**
   * Every caller that narrows this list to one row (via {@code getFirst()}/{@code findFirst()})
   * wants the manifest whose {@code name} actually names a stored file -- the row from the
   * manifest's ORIGINAL push. {@code ManifestTxService#createManifestForMultiPlatform} adds a
   * second, DB-only tracking row for the same digest when that manifest also becomes a
   * multi-platform tag's child (so panel listings can find it via that tag too); its {@code name}
   * is set to the digest itself and it has no storage file of its own. That tracking row is always
   * created strictly after the original, so ordering {@code ASC} and taking the first reliably
   * picks the storage-backed original. Confirmed live (RPS-1215's own investigation): with the
   * previous {@code DESC} order, `.getFirst()` picked the tracking row for any digest that had one,
   * and a raw `getResource` lookup built from its digest-shaped {@code name} then threw
   * `ItemNotFoundException("resourceNotFound")` -- a real, pre-existing bug, not introduced by
   * RPS-1215, only exposed by it (its own HEAD-by-digest fix stopped a real client's incidental
   * digest re-push from masking this by accident).
   */
  @Query(
      """
      select m from Manifest m
        left join m.tagPlatform tp
        join tp.tag t
        join t.image i
        join i.repo r
      where m.digest = :digest
        and i.id = :imageId
        and r.id = :repoId
        order by m.createdAt asc
    """)
  List<Manifest> findByRepoIdAndImageIdAndDigestList(UUID repoId, UUID imageId, String digest);

  @Query(
      """
      select m from Manifest m
      where m.tagPlatform.tag.id = :tagId
        and m.name like %:name%
    """)
  Page<ManifestListItem> findByTagIdAndNameContainsName(UUID tagId, String name, Pageable pageable);

  @Query(
      """
          select m from Manifest m
            left join m.layers l
            join l.repo r
            join Image i on r.id = i.repo.id
          where m.digest in :manifestDigests
            and r.id = :repoId
            and i.id = :imageId
        """)
  List<Manifest> findByRepoIdAndImageIdAndDigest(
      UUID repoId, UUID imageId, List<String> manifestDigests);

  @Query(
      """
          select m from Manifest m
            left join m.layers l
            join l.repo r
            join Image i on i.repo.id = r.id
          where m.digest = :digest
            and r.id = :repoId
            and i.id = :imageId
            and m.tagPlatform is null
        """)
  List<Manifest> findAllNonBindingManifestsByDigest(UUID repoId, UUID imageId, String digest);

  @Query(
      """
          select m from Manifest m
            left join fetch m.layers layers
            join layers.repo r
            join fetch m.tagPlatform tp
            join tp.tag.image image
          where r.id = :repoId
            and image.id = :imageId
            and tp.tag.id = :tagId
        """)
  Optional<Manifest> findByRepoIdAndImageIdAndTagId(UUID repoId, UUID imageId, UUID tagId);

  @Query(
      """
      select m
      from Manifest m
        join m.tagPlatform tp
        join tp.tag t
        join t.image i
        join i.repo r
      where
        r.id = :repoId
        and t.id = :tagId
        and i.id = :imageId
        and not exists (
          select 1
          from Manifest m2
            join m2.tagPlatform tp2
            join tp2.tag t2
          where
            m2.digest = m.digest
            and t2.image.id = :imageId
            and t2.id  <> :tagId
        )
    """)
  List<Manifest> findAllByRepoIdAndImageIdAndTagId(UUID repoId, UUID imageId, UUID tagId);

  @Query(
      """
      select m from Manifest m
        left join fetch m.tagPlatform tp
        join tp.tag t
        join t.image i
        join i.repo r
      where r.id = :repoId
        and i.id = :imageId
        and t.id = :tagId
        and m.platform != 'Multiplatform'
        and m.tagPlatform.tag is not null
    """)
  List<Manifest> findAllBindingManifestsAndNonMultiplatform(UUID repoId, UUID imageId, UUID tagId);

  @Query(
      """
        select m from Manifest m
          left join fetch m.tagPlatform tp
          join tp.tag t
          join t.image i
          join i.repo r
        where r.id = :repoId
          and i.id = :imageId
          and t.name = :name
          and m.platform = :platform
      """)
  Optional<Manifest> findByRepoIdAndImageIdAndTagIdAndPlatform(
      UUID repoId, UUID imageId, String name, String platform);

  @Query(
      """
      select m from Manifest m
        left join fetch m.tagPlatform tp
        join tp.tag t
        join t.image i
        join i.repo r
      where r.id = :repoId
        and i.id = :imageId
        and t.id = :tagId
        and m.digest = :digest
    """)
  Optional<Manifest> findByRepoIdAndImageIdAndTagIdAndDigest(
      UUID repoId, UUID imageId, UUID tagId, String digest);
}
