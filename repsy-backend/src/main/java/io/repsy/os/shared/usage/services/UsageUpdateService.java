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
package io.repsy.os.shared.usage.services;

import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UsageUpdateService {

  private final @NonNull RepoTxService repoTxService;

  @Async
  @Transactional
  public void updateUsage(final @NonNull UsageChangedInfo info) {
    this.updateRepoUsage(info.repoId(), info.usages().getDiskUsage());
  }

  /**
   * Adds the diff to the repo's disk usage, never taking it below zero.
   *
   * <p>The common case is one {@code UPDATE ... WHERE disk_usage + diff >= 0}, which takes the row
   * lock for the length of that statement only (RPS-2113). A diff that does not fit, or a repo that
   * no longer exists, updates no row; only a negative diff can be the first, so it falls back to
   * the locked read that tells the two apart, clamps and logs the drift.
   *
   * <p>The usage can only go negative when the accounting has drifted (a delete recorded twice, or
   * a size computed larger than what was added). {@code ch_repo__disk_usage} rejects a negative
   * value, which would fail the whole update and lose the diff, so the diff is clamped to what is
   * left and the drift is logged instead. The row is locked while it is read, so a concurrent
   * update cannot change the usage between the read and the write.
   */
  private void updateRepoUsage(final @NonNull UUID repoId, final long diskUsageDiff) {
    if (this.repoTxService.tryAddDiskUsage(repoId, diskUsageDiff)) {
      return;
    }

    if (diskUsageDiff >= 0) {
      // A non-negative diff always fits, so the repo is the thing that is missing.
      log.debug(
          "Repo {} no longer exists, skipping disk usage update of {}", repoId, diskUsageDiff);
      return;
    }

    this.clampRepoUsage(repoId, diskUsageDiff);
  }

  private void clampRepoUsage(final @NonNull UUID repoId, final long diskUsageDiff) {
    final var currentDiskUsage = this.repoTxService.findDiskUsageForUpdate(repoId);

    if (currentDiskUsage.isEmpty()) {
      // The repo was deleted after this update was submitted, so there is nothing to update.
      log.debug(
          "Repo {} no longer exists, skipping disk usage update of {}", repoId, diskUsageDiff);
      return;
    }

    long appliedDiff = diskUsageDiff;
    if (currentDiskUsage.get() + diskUsageDiff < 0) {
      appliedDiff = -currentDiskUsage.get();
      log.error(
          "Repo {} disk usage would be negative: current {}, diff {}, clamping it to 0",
          repoId,
          currentDiskUsage.get(),
          diskUsageDiff);
    }

    this.repoTxService.updateDiskUsage(repoId, appliedDiff);
  }
}
