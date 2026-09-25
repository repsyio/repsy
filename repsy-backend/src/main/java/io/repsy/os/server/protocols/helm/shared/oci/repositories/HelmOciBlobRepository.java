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
package io.repsy.os.server.protocols.helm.shared.oci.repositories;

import io.repsy.os.server.protocols.helm.shared.oci.entities.HelmOciBlob;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
@NullMarked
public interface HelmOciBlobRepository extends JpaRepository<HelmOciBlob, UUID> {

  Optional<HelmOciBlob> findByRepoIdAndDigest(UUID repoId, String digest);

  /**
   * Inserts the blob unless a row for the (repo, digest) pair exists, without failing the
   * transaction on the unique index when a concurrent upload of the same blob inserted it first.
   * The blob file is already stored when this runs, so the transaction cannot be repeated (the
   * upload it would finalise is gone), and on PostgreSQL a failed statement aborts it (RPS-1342).
   *
   * @return the number of rows inserted, 0 when the blob already existed
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          insert into "public"."helm_oci_blob"
            ("id", "version_lock", "repo_id", "digest", "size", "media_type", "created_at")
            values (:id, 0, :repoId, :digest, :size, :mediaType, :now)
            on conflict do nothing
          """,
      nativeQuery = true)
  int insertIfAbsent(UUID id, UUID repoId, String digest, long size, String mediaType, Instant now);

  @Modifying
  @Query("delete from HelmOciBlob b where b.repo.id = :repoId and b.digest = :digest")
  void deleteByRepoIdAndDigest(UUID repoId, String digest);
}
