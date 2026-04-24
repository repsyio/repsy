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

import static io.repsy.os.shared.utils.UsageUtils.humanReadable;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.generated.model.RepoUsageInfo;
import io.repsy.os.generated.model.TotalUsageInfo;
import io.repsy.os.generated.model.UsageInfo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class UsageService {

  private final @NonNull RepoRepository repoRepository;

  public @NonNull RepoUsageInfo getRepoUsageInfo(
      final @NonNull String repoName, final @NonNull RepoType repoType) {

    final var repo =
        this.repoRepository
            .findByNameAndType(repoName, repoType)
            .orElseThrow(() -> new ItemNotFoundException("repoNotFound"));

    final var diskUsed = this.createUsageInfo(repo.getDiskUsage());

    return RepoUsageInfo.builder().diskUsed(diskUsed).build();
  }

  public @NonNull TotalUsageInfo getTotalUsageInfo() {
    final var totalDiskUsage = this.repoRepository.getTotalDiskUsage();
    final var diskUsage = totalDiskUsage != null ? totalDiskUsage : 0L;

    final var diskUsed = this.createUsageInfo(diskUsage);
    final var reposCount = this.repoRepository.count();

    return TotalUsageInfo.builder().diskUsed(diskUsed).reposCount(reposCount).build();
  }

  private @NonNull UsageInfo createUsageInfo(final long value) {
    return UsageInfo.builder().value(value).text(humanReadable(value)).build();
  }
}
