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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
@NullMarked
public interface ImageRepository extends JpaRepository<Image, UUID> {

  List<Image> findAllByRepoId(UUID repoId);

  Optional<Image> findByRepoIdAndName(UUID repoId, String name);

  @Query(
      """
            select
              i.name as name,
              i.size as size,
              i.digest as digest,
              (
                select max(t.createdAt)
                from Tag t
                where t.image = i
              ) as updatedAt
            from Image i
              join i.repo re
            where re.id = :repoId
              and i.name like %:name%
          """)
  Page<ImageListItem> findAllByRepoIdAndContainsName(UUID repoId, String name, Pageable pageable);

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
  void updateImageSizeAndDigest(UUID repoId, UUID imageId, String digest, long size, Instant now);
}
