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

import io.repsy.os.server.protocols.docker.shared.tag.entities.ManifestChild;
import io.repsy.os.server.protocols.docker.shared.tag.entities.ManifestChildId;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
@NullMarked
public interface ManifestChildRepository extends JpaRepository<ManifestChild, ManifestChildId> {

  @Query("select c from ManifestChild c join fetch c.child where c.parent.id = :parentId")
  List<ManifestChild> findAllByParentId(UUID parentId);

  /** Every index-to-child edge among the manifests of an image, without loading the manifests. */
  @Query(
      """
      select c.parent.id as parentId, c.child.id as childId
      from ManifestChild c
      where c.parent.image.id = :imageId
    """)
  List<ManifestEdgeView> findEdgesByImageId(UUID imageId);
}
