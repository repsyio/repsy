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
              ) as tagCount,
              (
                select count(um)
                from Manifest um
                where um.image = i
                  and not exists (select 1 from Tag ut where ut.manifest = um)
                  and not exists (
                    select 1 from ManifestChild uc, Tag ut2
                    where uc.child = um and ut2.manifest = uc.parent)
              ) as untaggedManifestCount,
              (
                select coalesce(sum(ul.size), 0)
                from Layer ul
                where ul.id in (
                    select ul2.id from Layer ul2
                      join ul2.manifests um2
                    where um2.image = i
                      and not exists (select 1 from Tag ut3 where ut3.manifest = um2)
                      and not exists (
                        select 1 from ManifestChild uc2, Tag ut4
                        where uc2.child = um2 and ut4.manifest = uc2.parent)
                  )
                  and ul.id not in (
                    select tl.id from Layer tl
                      join tl.manifests tm
                    where tm.image = i
                      and (
                        exists (select 1 from Tag tt where tt.manifest = tm)
                        or exists (
                          select 1 from ManifestChild tc, Tag tt2
                          where tc.child = tm and tt2.manifest = tc.parent)
                      )
                  )
              ) as untaggedSize
            from Image i
              join i.repo re
          """;

  /**
   * The images of a repo as the panel lists them. An image stays while it stores any manifest, so a
   * row may have no tag: {@code tagCount} is 0 then, and {@code size} and {@code digest}, which
   * describe what the tags reach, are 0 and null.
   *
   * <p>{@code untaggedManifestCount} counts the manifests of the image that no tag points at and no
   * tagged index lists, which is what "Delete untagged manifests" removes (an index that lists
   * another index is followed one level here, the cleanup follows it all the way). {@code
   * untaggedSize} is the size of the distinct layers (config blobs included) those manifests link
   * to and no tagged manifest of the image links to: the bytes the image stores only for its
   * untagged manifests, and the whole stored size of an image without tags. A layer is stored once
   * per repo, so this is the image's own view, not what deleting it would free.
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
}
