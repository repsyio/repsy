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
package io.repsy.os.server.protocols.docker.ui.controllers;

import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.generated.model.UntaggedManifestCleanupResult;
import io.repsy.os.server.protocols.docker.shared.layer.dtos.OrphanLayerInfo;
import io.repsy.os.server.protocols.docker.ui.facades.DockerApiFacade;
import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.os.shared.http.ResponseEntities;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.shared.repo.dtos.Permission;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The repo-wide cleanups of a Docker repo. The repo comes first and the literal after it, so no
 * literal sits where a repo name can (API guideline, "URL shape").
 */
@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/repos/{repoName}/docker")
@SuppressWarnings("java:S6856")
public class DockerRepoCleanupController {

  private final @NonNull DockerApiFacade dockerApiFacade;
  private final @NonNull UsageUpdateService usageUpdateService;

  @DeleteMapping("/orphan-layers")
  @RepoOperation(permission = Permission.MANAGE)
  public ResponseEntity<Void> deleteOrphanLayers(final RepoInfo repoInfo) {

    this.dockerApiFacade.deleteOrphanLayers(repoInfo);

    return ResponseEntities.noContent();
  }

  /**
   * Deletes the manifests no tag points to, then the layers only they used. The manifest files are
   * gone, and their bytes refunded, before the response; the layer blobs are deleted in the
   * background and refund themselves.
   */
  @DeleteMapping("/untagged-manifests")
  @RepoOperation(permission = Permission.MANAGE)
  public ResponseEntity<UntaggedManifestCleanupResult> deleteUntaggedManifests(
      final RepoInfo repoInfo, @RequestParam(required = false) final String image) {

    final var manifests = this.dockerApiFacade.deleteUntaggedManifests(repoInfo, image);

    this.usageUpdateService.updateUsage(
        new UsageChangedInfo(
            repoInfo.getStorageKey(),
            BaseUsages.builder().diskUsage(-1L * manifests.freedBytes()).build()));

    final var orphans = this.dockerApiFacade.deleteOrphanLayers(repoInfo);

    final var result =
        new UntaggedManifestCleanupResult()
            .deletedManifests(manifests.deletedManifests())
            .freedManifestBytes(manifests.freedBytes())
            .orphanLayersScheduled(orphans.size())
            .orphanLayerBytes(orphans.stream().mapToLong(OrphanLayerInfo::size).sum());

    return ResponseEntity.ok(result);
  }
}
