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

import io.repsy.os.server.protocols.docker.shared.tag.entities.TagPlatform;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
@NullMarked
public interface TagPlatformRepository extends JpaRepository<TagPlatform, UUID> {

  Optional<TagPlatform> findByTagImageRepoIdAndTagImageIdAndTagIdAndPlatform(
      UUID repoId, UUID imageId, UUID tagId, String platform);

  List<TagPlatform> findAllByTagId(UUID id);

  List<TagPlatform> findAllByTagImageId(UUID id);
}
