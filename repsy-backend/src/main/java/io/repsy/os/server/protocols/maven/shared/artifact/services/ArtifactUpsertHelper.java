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
package io.repsy.os.server.protocols.maven.shared.artifact.services;

import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import lombok.RequiredArgsConstructor;
import org.apache.maven.model.Model;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@NullMarked
class ArtifactUpsertHelper {

  private final ArtifactRepository artifactRepository;
  private final ArtifactVersionRepository artifactVersionRepository;
  private final ArtifactVersionWriteService artifactVersionWriteService;

  /**
   * Inserts a new maven_artifact row in its own REQUIRES_NEW transaction.
   *
   * <p>Runs in a separate transaction so that a unique-constraint violation (concurrent insert of
   * the same repo+group+artifact) only rolls back this inner transaction, not the caller's. The
   * caller can then catch {@link org.springframework.dao.DataIntegrityViolationException} and
   * re-fetch the row that the winning thread committed.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  Artifact insertArtifact(final Artifact artifact) {
    return this.artifactRepository.saveAndFlush(artifact);
  }

  /**
   * Inserts a new maven_artifact_version row, together with its dependent developer/license rows
   * and the parent artifact's latest/release update, in a single REQUIRES_NEW transaction.
   *
   * <p>Same isolation rationale as {@link #insertArtifact}: a concurrent insert of the same
   * (artifact, version) only rolls back this inner transaction. The dependent writes are included
   * in this same transaction (rather than left to the caller's outer transaction) so that a failure
   * in any of them rolls back the version row too, instead of leaving it committed but orphaned (no
   * developers/licenses, stale latest/release on the artifact). The dependent-write logic itself
   * lives in {@link ArtifactVersionWriteService}, shared with {@link ArtifactServiceImpl}'s
   * "already exists" update path, so there is a single implementation.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  ArtifactVersion insertArtifactVersion(
      final ArtifactVersion version, final @Nullable Model pomModel, final Artifact artifact) {

    final var savedVersion = this.artifactVersionRepository.saveAndFlush(version);

    this.artifactVersionWriteService.createVersionDevelopers(pomModel, savedVersion);
    this.artifactVersionWriteService.createVersionLicenses(pomModel, savedVersion);
    this.artifactVersionWriteService.updateReleaseAndLatestVersion(artifact);

    return savedVersion;
  }
}
