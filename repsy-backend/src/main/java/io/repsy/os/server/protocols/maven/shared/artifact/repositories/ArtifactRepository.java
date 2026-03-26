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

import io.repsy.os.server.protocols.maven.shared.artifact.dtos.ArtifactListItem;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
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
public interface ArtifactRepository extends JpaRepository<Artifact, UUID> {

  @Query(
      """
          select a.groupName from Artifact a where a.repo.id = :repoId
          """)
  @NonNull List<String> findGroupNamesByRepoId(UUID repoId);

  long countByRepoIdAndGroupName(UUID repoId, @NonNull String groupName);

  @Query(
      """
          select a from Artifact a
          where a.repo.id = :repoId and a.groupName like %:groupName%""")
  @NonNull Page<ArtifactListItem> findAllByRepoIdAndContainsGroupName(
      UUID repoId, @NonNull String groupName, @NonNull Pageable pageable);

  @Query(
      """
          select a from Artifact a
          where a.repo.id = :repoId and a.groupName = :groupName and a.artifactName
          like %:artifactName%""")
  @NonNull Page<ArtifactListItem> findAllByRepoIdContainsArtifactName(
      UUID repoId,
      @NonNull String groupName,
      @NonNull String artifactName,
      @NonNull Pageable pageable);

  @NonNull List<Artifact> findAllByRepoIdAndGroupName(UUID repoId, @NonNull String groupName);

  @NonNull Optional<Artifact> findByRepoIdAndGroupNameAndArtifactName(
      UUID repoId, @NonNull String groupName, @NonNull String artifactName);
}
