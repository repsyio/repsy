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
package io.repsy.os.server.protocols.docker.shared.image.repositories;

import io.repsy.os.server.protocols.docker.shared.image.dtos.ImageListItem;
import io.repsy.os.server.protocols.docker.shared.image.entities.Image;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
@NullMarked
public interface ImageRepository extends JpaRepository<Image, UUID> {

  List<Image> findAllByRepoId(UUID repoId);

  Optional<Image> findByRepoIdAndName(UUID repoId, String name);

  /** The columns of an image as the panel lists it, from {@code Image i join i.repo re}. */
  String LIST_ITEM_SELECT =
      """

            select
              i.id as id,
              i.name as name,
              i.size as size,
              i.digest as digest,
              (
                select max(t.createdAt)
                from Tag t
                where t.image = i
              ) as updatedAt,
              i.lastUpdatedAt as lastUpdatedAt,
              (
                select count(t2)
                from Tag t2
                where t2.image = i
              ) as tagCount
            from Image i
              join i.repo re
          """;

  /**
   * The images of a repo as the panel lists them. An image stays while it stores any manifest, so a
   * row may have no tag: {@code tagCount} is 0 then, and {@code size} and {@code digest}, which
   * describe what the tags reach, are 0 and null. The untagged manifests and their size are not
   * columns here: they follow indexes of any depth, which a JPQL subquery cannot, so {@link
   * #findUntaggedStatsByImageId} computes them per image.
   */
  @Query(
      LIST_ITEM_SELECT
          + """
            where re.id = :repoId
              and i.name like %:name%
          """)
  Page<ImageListItem> findAllByRepoIdAndContainsName(UUID repoId, String name, Pageable pageable);

  /** The one image of the repo with exactly this name, listed the same way. */
  @Query(
      LIST_ITEM_SELECT
          + """
            where re.id = :repoId
              and i.name = :name
          """)
  Optional<ImageListItem> findListItemByRepoIdAndName(UUID repoId, String name);

  /**
   * The image row, locked against a concurrent delete-if-empty: a push and a delete of the last
   * manifest of an image meet here. {@code PESSIMISTIC_WRITE} is {@code FOR UPDATE}.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select i from Image i where i.id = :imageId")
  Optional<Image> findByIdForUpdate(UUID imageId);

  /**
   * The image row, share-locked: a push holds it until it commits so that a delete of the image's
   * last manifest waits, without making pushes into one image wait for each other. {@code
   * PESSIMISTIC_READ} is {@code FOR SHARE}.
   */
  @Lock(LockModeType.PESSIMISTIC_READ)
  @Query("select i from Image i where i.id = :imageId")
  Optional<Image> findByIdForShare(UUID imageId);

  /**
   * Inserts the image unless one with the same (repo, name) exists, without failing the transaction
   * on the unique index: on PostgreSQL a failed statement aborts the transaction, which here also
   * holds the manifest write. When a concurrent push has inserted the image and not committed yet,
   * the statement waits for it: it does nothing when that push commits, and inserts when it rolls
   * back (RPS-1350).
   *
   * @return 1 when the row was inserted, 0 when it already existed
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          insert into "public"."docker_image" ("id", "repo_id", "name", "size", "created_at", "last_updated_at")
            values (:id, :repoId, :name, 0, :now, :now)
            on conflict do nothing
          """,
      nativeQuery = true)
  int insertIfAbsent(UUID id, UUID repoId, String name, Instant now);

  @Modifying
  @Query(
      """
        update Image i set
          i.size = :size,
          i.digest = :digest,
          i.lastUpdatedAt = :now
        where i.id = :imageId
          and i.repo.id = :repoId
      """)
  void updateImageSizeAndDigest(
      UUID repoId, UUID imageId, @Nullable String digest, long size, Instant now);

  /**
   * What the image stores only for manifests that no tag reaches, computed the way "Delete untagged
   * manifests" computes it ({@code UntaggedManifestFinder}): a manifest is reached when a tag
   * points at it or at an index that lists it, directly or through other indexes, to any depth. The
   * recursive CTE follows the index edges to the end.
   *
   * <p>{@code manifestCount} is the number of manifests no tag reaches; {@code size} is the size of
   * the distinct layers (config blobs included) those manifests link to and no reached manifest
   * links to.
   */
  @Query(
      value =
          """
          with recursive reach(manifest_id) as (
            select t.manifest_id from docker_tag t where t.image_id = :imageId
            union
            select c.child_id from docker_manifest_child c
              join reach r on c.parent_id = r.manifest_id
          )
          select
            (
              select count(*) from docker_manifest m
              where m.image_id = :imageId
                and m.id not in (select manifest_id from reach)
            ) as "manifestCount",
            cast(coalesce((
              select sum(l.size) from docker_layer l
              where l.id in (
                  select ml.layer_id from docker_manifest_layer ml
                    join docker_manifest um on um.id = ml.manifest_id
                  where um.image_id = :imageId
                    and um.id not in (select manifest_id from reach)
                )
                and l.id not in (
                  select rl.layer_id from docker_manifest_layer rl
                  where rl.manifest_id in (select manifest_id from reach)
                )
            ), 0) as bigint) as "size"
          """,
      nativeQuery = true)
  UntaggedStats findUntaggedStatsByImageId(UUID imageId);

  /** The untagged manifests of an image and the size only they store. */
  interface UntaggedStats {

    Long getManifestCount();

    Long getSize();
  }
}
