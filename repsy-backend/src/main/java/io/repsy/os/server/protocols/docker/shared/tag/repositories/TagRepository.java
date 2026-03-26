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

import io.repsy.os.server.protocols.docker.shared.tag.dtos.ImageTagListItem;
import io.repsy.os.server.protocols.docker.shared.tag.entities.Tag;
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
public interface TagRepository extends JpaRepository<Tag, UUID> {

  Optional<Tag> findByImageRepoIdAndImageNameAndName(UUID repoId, String imageName, String tagName);

  Optional<Tag> findDistinctFirstByImageRepoIdAndImageNameAndDigestOrderByCreatedAtDesc(
      UUID repoId, String imageName, String digest);

  @Query(
      """
        select t from Tag t
        where t.image.name = :imageName
        and t.image.repo.id = :repoId
        and t.name like %:name%""")
  Page<ImageTagListItem> findAllByImageRepoIdAndImageNameContainsName(
      UUID repoId, String imageName, String name, Pageable pageable);

  List<Tag> findAllByImageRepoIdAndImageId(UUID repoId, UUID imageId);

  Optional<Tag> findByImageRepoIdAndImageIdAndName(UUID repoId, UUID imageId, String name);
}
