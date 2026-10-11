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

import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.maven.shared.utils.MavenGavUtils;
import io.repsy.protocols.maven.shared.utils.MavenPublishLimits;
import io.repsy.protocols.maven.shared.utils.PomModelUtils;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.index.artifact.Gav;
import org.apache.maven.model.Model;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Registers a stored POM (RPS-2167, split out of {@link ArtifactDeploymentService}): reads it,
 * checks that it may be registered, and has the rows written around the verification of the
 * signatures that arrived before it. It has no transaction of its own, and neither has {@link
 * ArtifactDeploymentService#createOrUpdateArtifact} that calls it (RPS-2176): the checks of the
 * parked signatures and the row inserts run in transactions of their own ({@code REQUIRES_NEW}), so
 * a transaction around them held a pooled connection while they needed a second one. The version
 * lock and the recomputation of {@code signed} are the transaction of {@link
 * ArtifactSignatureService#updateSignedForFile}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@NullMarked
class ArtifactPomRegistrationService {

  private final RepoTxService repoTxService;
  private final ArtifactRowWriteService artifactRowWriteService;
  private final PendingSignatureService pendingSignatureService;
  private final ArtifactSignatureService artifactSignatureService;
  private final MavenPluginMetadataService mavenPluginMetadataService;

  /** Registers the POM of {@code storagePath}, if it is a valid one of its path's group. */
  void register(
      final BaseRepoInfo<UUID> repoInfo, final StoragePath storagePath, final Resource resource) {

    final var repo = this.repoTxService.requireRepo(repoInfo.getName(), RepoType.MAVEN);

    final var gav = MavenGavUtils.convertPathToGav(storagePath.getRelativePath().getPath());
    final var pomModel = PomModelUtils.readModel(resource);

    if (this.checkExtractedInfos(pomModel, gav, storagePath, repo)) {
      assert gav != null;
      // After the checks above, which read the parent: an over-long descriptive value is dropped
      // rather than failing the row insert (RPS-1138).
      MavenPublishLimits.dropOverLongFields(pomModel);
      this.registerPom(repoInfo, storagePath, repo, gav, pomModel);
    }
  }

  /**
   * Registers the version of a stored POM. On a repo that verifies every signature (RPS-1188) the
   * signatures that arrived before it are dealt with around that: the ones of files that are stored
   * are verified before anything is registered, so one that does not verify fails the POM's upload
   * and leaves no version behind, and are written and recorded once the version exists. The POM's
   * own row is then not forgotten: its signature, if it was parked, was just recorded.
   */
  private void registerPom(
      final BaseRepoInfo<UUID> repoInfo,
      final StoragePath storagePath,
      final Repo repo,
      final Gav gav,
      final @Nullable Model pomModel) {

    final var versionPath = ArtifactSignatureService.versionPathOf(storagePath);
    final var verifyAll = repoInfo.isPgpVerifyAllSignaturesEnabled();

    if (verifyAll) {
      this.pendingSignatureService.verifyDirectory(repoInfo, versionPath);
    }

    final var prefix =
        this.mavenPluginMetadataService.resolvePluginPrefix(repoInfo, storagePath, gav, pomModel);

    this.artifactRowWriteService.createOrUpdateArtifactByPomFile(
        repo, gav, versionPath, pomModel, prefix);

    final var recorded =
        verifyAll
            && this.pendingSignatureService
                .reconcileDirectory(repoInfo, versionPath)
                .contains(PendingSignatureService.pathOf(storagePath));

    this.artifactSignatureService.updateSignedForFile(repoInfo, storagePath, recorded);
  }

  private boolean checkExtractedInfos(
      final @Nullable Model pomModel,
      final @Nullable Gav gav,
      final StoragePath storagePath,
      final Repo repo) {

    if (this.isInvalidGav(gav)) {
      log.error(
          "Maven Gav could not be calculated for repo {} for file {}",
          repo.getName(),
          storagePath.getPath());
      return false;
    } else if (this.isInvalidPomModel(pomModel)) {
      log.error(
          "Maven Pom model could not be calculated for repo {} for file {}",
          repo.getName(),
          storagePath.getPath());
      return false;
    }

    // The upload refuses such a POM before it is stored (RPS-1193). This stays as a logged skip for
    // a POM stored before that, or reached by another path: it must still not be registered.
    final var declared = PomModelUtils.declaredGroupId(pomModel);

    if (declared != null && !declared.equals(gav.getGroupId())) {
      log.warn(
          "Stored POM {} declares groupId {} under group {}, not registered (refused before the"
              + " store since RPS-1193)",
          storagePath.getPath(),
          declared,
          gav.getGroupId());
      return false;
    }

    return true;
  }

  private boolean isInvalidGav(final @Nullable Gav gav) {

    return gav == null || gav.isHash();
  }

  private boolean isInvalidPomModel(final @Nullable Model model) {

    return model == null;
  }
}
