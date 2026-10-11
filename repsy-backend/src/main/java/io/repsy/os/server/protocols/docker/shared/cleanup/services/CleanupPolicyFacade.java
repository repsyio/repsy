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
package io.repsy.os.server.protocols.docker.shared.cleanup.services;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.server.protocols.docker.shared.layer.dtos.OrphanLayerInfo;
import io.repsy.os.server.protocols.docker.shared.layer.services.OrphanLayerService;
import io.repsy.os.server.protocols.docker.shared.tag.services.TagDeleter;
import io.repsy.os.server.protocols.docker.shared.tag.services.UntaggedManifestCleanupService;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Runs the cleanup policies that are due: deletes the tags they select, then the untagged manifests
 * those tags left behind and the layers only they used. A failure on one tag or repo is logged and
 * the rest goes on.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CleanupPolicyFacade {

  private final CleanupPolicyService cleanupPolicyService;
  private final TagDeleter tagDeleter;
  private final UntaggedManifestCleanupService untaggedManifestCleanupService;
  private final OrphanLayerService orphanLayerService;
  private final UsageUpdateService usageUpdateService;

  // No ambient transaction: processCleanup() commits the new run windows before any tag goes, and
  // every deletion below has its own transaction.
  public void executeCleanup() {

    final var deletions = this.cleanupPolicyService.processCleanup();
    log.info("Processing {} tag deletions", deletions.size());

    // What the tag deletions left behind, per repo and image, and how long it has to be untagged.
    final var touched = new LinkedHashMap<RepoInfo, Map<String, Integer>>();

    for (final var info : deletions) {
      try {
        this.tagDeleter.deleteTag(info.repoInfo(), info.imageName(), info.tagName());

        touched
            .computeIfAbsent(info.repoInfo(), repo -> new LinkedHashMap<>())
            .merge(info.imageName(), info.keepDays(), Math::min);
      } catch (final RuntimeException e) {
        log.error(
            "Failed to delete tag '{}' of image '{}' in repository '{}'",
            info.tagName(),
            info.imageName(),
            info.repoInfo().getName(),
            e);
      }
    }

    touched.forEach(this::releaseUntaggedManifests);
  }

  /**
   * A tag delete frees nothing: the manifest the tag pointed at stays, untagged and pullable by its
   * digest (RPS-1216). The policy exists to give the space back, so the manifests of the images it
   * just cleaned that no tag reaches, and have not for the policy's retention, are deleted now with
   * their files, and the layers only they used are swept. A manifest younger than the retention is
   * left alone, so a digest-pushed manifest of a push still running is never taken.
   */
  private void releaseUntaggedManifests(
      final RepoInfo repoInfo, final Map<String, Integer> keepDaysByImage) {

    var freedBytes = 0L;

    for (final var entry : keepDaysByImage.entrySet()) {
      try {
        final var olderThan = Instant.now().minus(Duration.ofDays(entry.getValue()));

        freedBytes +=
            this.untaggedManifestCleanupService
                .deleteUntagged(repoInfo, entry.getKey(), olderThan)
                .freedBytes();
      } catch (final RuntimeException e) {
        log.error(
            "Failed to delete the untagged manifests of image '{}' in repository '{}'",
            entry.getKey(),
            repoInfo.getName(),
            e);
      }
    }

    if (freedBytes > 0) {
      this.usageUpdateService.updateUsage(
          new UsageChangedInfo(
              repoInfo.getStorageKey(), BaseUsages.builder().diskUsage(-1L * freedBytes).build()));
    }

    try {
      final var orphans = this.orphanLayerService.deleteOrphanLayers(repoInfo);

      log.info(
          "Cleanup policy of repository '{}': {} orphan layers scheduled for deletion ({} bytes)",
          repoInfo.getName(),
          orphans.size(),
          orphans.stream().mapToLong(OrphanLayerInfo::size).sum());
    } catch (final RuntimeException e) {
      log.error("Failed to sweep the orphan layers of repository '{}'", repoInfo.getName(), e);
    }
  }
}
