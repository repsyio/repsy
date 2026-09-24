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

import io.repsy.os.server.protocols.docker.shared.tag.entities.Manifest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * A manifest is one row per (image, {@code sha256} digest), whatever tags point at it. The queries
 * that take a digest of either algorithm look at both digest columns: an OCI digest names its
 * algorithm ({@code sha256:...} or {@code sha512:...}), so the two can never be confused.
 */
@Repository
@NullMarked
public interface ManifestRepository extends JpaRepository<Manifest, UUID> {

  Optional<Manifest> findByImageIdAndDigest(UUID imageId, String digest);

  @Query(
      """
      select m from Manifest m
      where m.image.id = :imageId
        and (m.digest = :digest or m.digestSha512 = :digest)
    """)
  Optional<Manifest> findByImageIdAndAnyDigest(UUID imageId, String digest);

  boolean existsByImageIdAndConfigDigest(UUID imageId, String configDigest);

  List<Manifest> findAllByImageId(UUID imageId);

  /** Just what identifies the files of an image's manifests, without loading the entities. */
  List<ManifestFileView> findFileViewsByImageId(UUID imageId);

  /**
   * How many manifests of the repo carry the digest: a manifest file is stored once per repo and
   * digest, shared by every image that has the manifest, so it may only be deleted when this drops
   * to zero.
   */
  long countByImageRepoIdAndDigest(UUID repoId, String digest);

  /**
   * The manifests of an image that still keep their file under the name an earlier version gave it,
   * so a file is only removed by the name once no other row of the image uses it.
   */
  long countByImageIdAndStorageName(UUID imageId, String storageName);

  /**
   * A batch of the manifests {@code DockerManifestLayoutRepairService} has work for, in id order:
   * rows whose file still has its legacy name or that have no {@code sha512} digest yet.
   */
  @Query(
      """
      select m.id from Manifest m
      where m.storageName is not null or m.digestSha512 is null
      order by m.id
    """)
  List<UUID> findRepairableIds(Pageable pageable);

  /** The batch that follows {@link #findRepairableIds}, after the last id it returned. */
  @Query(
      """
      select m.id from Manifest m
      where (m.storageName is not null or m.digestSha512 is null)
        and m.id > :after
      order by m.id
    """)
  List<UUID> findRepairableIdsAfter(UUID after, Pageable pageable);
}
