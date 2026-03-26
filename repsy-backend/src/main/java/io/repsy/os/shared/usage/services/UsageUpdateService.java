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

  private void updateRepoUsage(final @NonNull UUID repoId, final long diskUsageDiff) {
    final var repo = this.repoTxService.getRepoEntity(repoId);

    this.repoTxService.updateDiskUsage(repoId, diskUsageDiff);

    final var newDiskUsage = repo.getDiskUsage() + diskUsageDiff;

    if (newDiskUsage < 0) {
      log.error("Repo {} disk usage is negative: {}", repo.getName(), newDiskUsage);
    }
  }
}
