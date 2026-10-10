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
import java.util.Collection;
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

  /**
   * The columns of an image as the panel lists it, from {@code Image i join i.repo re}. {@code
   * updatedAt} and {@code tagCount} are stored columns ({@link #refreshTagStats}, RPS-2120), so
   * sorting a repo's images by {@code updatedAt} no longer runs a subquery per image.
   */
  String LIST_ITEM_SELECT =
      """

            select
              i.id as id,
              i.name as name,
              i.size as size,
              i.digest as digest,
              i.lastTagAt as updatedAt,
              i.lastUpdatedAt as lastUpdatedAt,
              cast(i.tagCount as long) as tagCount
            from Image i
              join i.repo re
          """;

  /**
   * The images of a repo as the panel lists them. An image stays while it stores any manifest, so a
   * row may have no tag: {@code tagCount} is 0 then, and {@code size} and {@code digest}, which
   * describe what the tags reach, are 0 and null. The untagged manifests and their size are not
   * columns here: they follow indexes of any depth, which a JPQL subquery cannot, so {@link
   * #findUntaggedStatsByImageIds} computes them for the page.
   */
  @Query(
      LIST_ITEM_SELECT
          + """
            where re.id = :repoId
              and lower(i.name) like :name escape '\\'
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
   * Recomputes {@code last_tag_at} and {@code tag_count} of the image from its tags (RPS-2120). The
   * statement runs after the image row's lock is taken by {@link #updateImageSizeAndDigest} in the
   * same transaction, so it is a statement of its own with a fresh snapshot: a concurrent
   * transaction that changed the image's tags has committed (it held the row lock) and is counted.
   * The tags changed by the calling transaction are flushed first.
   */
  @Modifying(flushAutomatically = true)
  @Query(
      """
        update Image i set
          i.lastTagAt = (select max(t.createdAt) from Tag t where t.image = i),
          i.tagCount = (select count(t2) from Tag t2 where t2.image = i)
        where i.id = :imageId
      """)
  void refreshTagStats(UUID imageId);

  /**
   * What each of the images stores only for manifests that no tag reaches, computed the way "Delete
   * untagged manifests" computes it ({@code UntaggedManifestFinder}): a manifest is reached when a
   * tag points at it or at an index that lists it, directly or through other indexes, to any depth.
   * The recursive CTE follows the index edges to the end, once for all the images: it is seeded
   * with the tags of every listed image and carries the image with each reached manifest, so a page
   * of images costs one query, not one per image (RPS-1566).
   *
   * <p>{@code manifestCount} is the number of manifests no tag reaches; {@code size} is the size of
   * the distinct layers (config blobs included) those manifests link to and no reached manifest
   * links to. Every listed image has a row, an image without manifests with 0 and 0.
   *
   * <p>The identifiers are quoted and schema-qualified, as in {@code insertIfAbsent}: the H2
   * migrations create lower-case quoted names, which H2 matches case-sensitively (RPS-1385).
   */
  @Query(
      value =
          """
          with recursive reach(image_id, manifest_id) as (
            select t."image_id", t."manifest_id" from "public"."docker_tag" t
              where t."image_id" in (:imageIds)
            union
            select r.image_id, c."child_id" from "public"."docker_manifest_child" c
              join reach r on c."parent_id" = r.manifest_id
          )
          select
            cast(i."id" as varchar(36)) as "imageId",
            (
              select count(*) from "public"."docker_manifest" m
              where m."image_id" = i."id"
                and not exists (
                  select 1 from reach r where r.image_id = i."id" and r.manifest_id = m."id"
                )
            ) as "manifestCount",
            cast(coalesce((
              select sum(l."size") from "public"."docker_layer" l
              where l."id" in (
                  select ml."layer_id" from "public"."docker_manifest_layer" ml
                    join "public"."docker_manifest" um on um."id" = ml."manifest_id"
                  where um."image_id" = i."id"
                    and not exists (
                      select 1 from reach r where r.image_id = i."id" and r.manifest_id = um."id"
                    )
                )
                and l."id" not in (
                  select rl."layer_id" from "public"."docker_manifest_layer" rl
                    join reach rr on rr.manifest_id = rl."manifest_id"
                  where rr.image_id = i."id"
                )
            ), 0) as bigint) as "size"
          from "public"."docker_image" i
          where i."id" in (:imageIds)
          """,
      nativeQuery = true)
  List<UntaggedStats> findUntaggedStatsByImageIds(Collection<UUID> imageIds);

  /** The untagged manifests of an image and the size only they store. */
  interface UntaggedStats {

    /** The id as text: H2 hands a native {@code uuid} column to a projection as bytes. */
    String getImageId();

    Long getManifestCount();

    Long getSize();
  }

  /**
   * A tagged image that a release before RPS-1216 pushed without ever filling {@code size} and
   * {@code digest} (RPS-1563): {@code refreshImageSize} sets the digest from the image's most
   * recently moved tag, so a tagged image that still has none was never recomputed. An image with
   * no tag is untagged on purpose and keeps {@code digest} null, so it never matches. Ordered by id
   * for the same resumable, idempotent paging {@link
   * io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestRepository#findRepairableIds}
   * uses: a row that {@code
   * io.repsy.os.server.protocols.docker.shared.image.services.DockerImageStatsBackfillService}
   * recomputed no longer matches the query that found it.
   */
  @Query(
      """
      select i.id as imageId, i.repo.id as repoId from Image i
      where i.digest is null
        and exists (select 1 from Tag t where t.image = i)
      order by i.id
    """)
  List<ImageStatsBackfillRow> findBackfillableIds(Pageable pageable);

  /** The batch that follows {@link #findBackfillableIds}, after the last id it returned. */
  @Query(
      """
      select i.id as imageId, i.repo.id as repoId from Image i
      where i.digest is null
        and exists (select 1 from Tag t where t.image = i)
        and i.id > :after
      order by i.id
    """)
  List<ImageStatsBackfillRow> findBackfillableIdsAfter(UUID after, Pageable pageable);

  /** The (image, repo) pair a backfill batch needs, without loading the whole entity. */
  interface ImageStatsBackfillRow {

    UUID getImageId();

    UUID getRepoId();
  }
}
