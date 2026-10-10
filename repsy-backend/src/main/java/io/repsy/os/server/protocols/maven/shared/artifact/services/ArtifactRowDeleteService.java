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

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.PendingSignatureRepository;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * Deletes the artifact, version and group rows (RPS-2167, split out of {@link
 * ArtifactDeploymentService}), with the signatures parked under them. It has no transaction of its
 * own: {@link ArtifactDeploymentService} calls it inside its read write one.
 */
@Component
@RequiredArgsConstructor
@NullMarked
class ArtifactRowDeleteService {

  private final RepoTxService repoTxService;
  private final ArtifactRepository artifactRepository;
  private final ArtifactVersionRepository artifactVersionRepository;
  private final PendingSignatureRepository pendingSignatureRepository;
  private final ArtifactVersionWriteService artifactVersionWriteService;

  void deleteArtifact(final UUID repoId, final String groupName, final String artifactName) {

    final var artifact =
        this.artifactRepository
            .findByRepoIdAndGroupNameAndArtifactName(repoId, groupName, artifactName)
            .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.ARTIFACT_NOT_FOUND));

    this.artifactRepository.delete(artifact);
    this.dropPendingSignatures(repoId, groupPath(groupName) + "/" + artifactName + "/");
  }

  void deleteArtifactVersion(
      final RepoInfo repoInfo,
      final String groupName,
      final String artifactName,
      final String versionName) {

    final var artifact =
        this.artifactRepository
            .findByRepoIdAndGroupNameAndArtifactName(
                repoInfo.getStorageKey(), groupName, artifactName)
            .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.ARTIFACT_NOT_FOUND));

    final var artifactVersion =
        this.artifactVersionRepository
            .findByArtifactIdAndVersionName(artifact.getId(), versionName)
            .orElseThrow(
                () -> new ItemNotFoundException(ProtocolErrorCodes.ARTIFACT_VERSION_NOT_FOUND));

    this.artifactVersionRepository.delete(artifactVersion);
    this.dropPendingSignatures(
        repoInfo.getStorageKey(),
        groupPath(groupName) + "/" + artifactName + "/" + versionName + "/");

    // latest/release follow the rows that are left, as they do on upload. The file's own values are
    // not used: it lists only what a Maven client deployed, and an artifact published by Ivy or sbt
    // has none (RPS-1331).
    this.artifactVersionWriteService.updateReleaseAndLatestVersion(artifact);
  }

  @SuppressWarnings("all")
  void deleteGroup(final UUID repoId, final String groupName) {

    final var repo = this.repoTxService.requireRepo(repoId);

    final var artifacts =
        this.artifactRepository.findAllByRepoIdAndGroupName(repo.getId(), groupName);

    for (final Artifact artifact : artifacts) {
      // Do not change this with delete all method.
      this.artifactRepository.delete(artifact);
      this.dropPendingSignatures(
          repoId, groupPath(groupName) + "/" + artifact.getArtifactName() + "/");
    }
  }

  /**
   * Drops the signatures parked for files under {@code directoryPrefix}: a version that is deleted
   * and uploaded again must not meet the signature of the old one (RPS-1188).
   */
  private void dropPendingSignatures(final UUID repoId, final String directoryPrefix) {

    this.pendingSignatureRepository.deleteByRepoIdAndSignedFilePathStartingWith(
        repoId, directoryPrefix);
  }

  private static String groupPath(final String groupName) {

    return groupName.replace('.', '/');
  }
}
