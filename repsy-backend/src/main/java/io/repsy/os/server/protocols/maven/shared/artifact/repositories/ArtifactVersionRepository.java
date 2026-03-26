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
}
