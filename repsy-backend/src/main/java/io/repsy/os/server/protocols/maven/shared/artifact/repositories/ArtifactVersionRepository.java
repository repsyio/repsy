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
package io.repsy.os.server.protocols.maven.shared.artifact.repositories;

import io.repsy.os.server.protocols.maven.shared.artifact.dtos.ArtifactVersionListItem;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface ArtifactVersionRepository extends JpaRepository<ArtifactVersion, UUID> {

  @Query(
      """
        select count(av)
        from ArtifactVersion av
        join av.artifact a
        join a.repo r
        where
        r.id = :repoId
        and
        a.groupName = :groupName
        and
        a.artifactName = :artifactName""")
  long countByRepoIdAndGroupNameAndArtifactName(
      UUID repoId, @NonNull String groupName, @NonNull String artifactName);

  /** The versions of every artifact of a group, which is what deleting the group removes. */
  @Query(
      """
        select count(av)
        from ArtifactVersion av
        join av.artifact a
        where a.repo.id = :repoId
        and a.groupName = :groupName""")
  long countByRepoIdAndGroupName(UUID repoId, @NonNull String groupName);

  @Query(
      """
        select av from ArtifactVersion av
        join av.artifact a
        where a.repo.id = :repoId
        and a.groupName = :groupName
        and a.artifactName = :artifactName
        and av.versionName like %:versionName%
      """)
  @NonNull Page<ArtifactVersionListItem>
      findAllByRepoIdAndGroupNameAndArtifactNameContainsVersionName(
          @NonNull UUID repoId,
          @NonNull String groupName,
          @NonNull String artifactName,
          @NonNull String versionName,
          @NonNull Pageable pageable);

  @Query(
      """
        select av from ArtifactVersion av
        join av.artifact a
        join a.repo r
        where r.id = :repoId
        and a.groupName = :groupName
        and a.artifactName = :artifactName
      """)
  @NonNull Page<ArtifactVersionListItem> findAllByRepoIdAndGroupNameAndArtifactName(
      UUID repoId,
      @NonNull String groupName,
      @NonNull String artifactName,
      @NonNull Pageable pageable);

  @NonNull List<ArtifactVersion> findByArtifactId(UUID artifactId);

  @NonNull Optional<ArtifactVersion> findByArtifactIdAndVersionName(
      @NonNull UUID artifactId, @NonNull String versionName);

  /**
   * The ids of the versions of a repo after {@code after}, in id order and at most a page of them:
   * a repo of any size is walked page by page, and a version added meanwhile is either met or was
   * recomputed by its own upload (RPS-1316).
   */
  @Query(
      """
        select v.id from ArtifactVersion v
        where v.artifact.repo.id = :repoId and v.id > :after
        order by v.id
      """)
  @NonNull List<UUID> findIdsByRepoIdAfter(UUID repoId, UUID after, Pageable pageable);

  /**
   * Takes the row lock of a version without changing it: the statement is an update, so the lock is
   * the one an update takes (it does not block the insert of a signature row that references the
   * version) and it is held until the transaction ends (RPS-1188).
   */
  @Modifying(flushAutomatically = true)
  @Query("update ArtifactVersion v set v.signed = v.signed where v.id = :versionId")
  void lockForSignedUpdate(UUID versionId);

  @Modifying(flushAutomatically = true)
  @Query("update ArtifactVersion v set v.signed = :signed where v.id = :versionId")
  void updateSigned(UUID versionId, boolean signed);

  /**
   * The {@code pgpVerifyAllSignaturesEnabled} setting of the repo a version belongs to, as it is
   * committed now. It is a scalar query, so it goes to the database even when the repo row is
   * already in the persistence context of the transaction, with a value read before a toggle
   * committed (RPS-1323).
   */
  @Query(
      """
        select r.pgpVerifyAllSignaturesEnabled
        from ArtifactVersion v join v.artifact a join a.repo r
        where v.id = :versionId
      """)
  @NonNull Optional<Boolean> findVerifyAllSignaturesEnabledByVersionId(UUID versionId);
}
