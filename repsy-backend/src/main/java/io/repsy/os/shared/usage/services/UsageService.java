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

import io.repsy.os.generated.model.RepoUsageInfo;
import io.repsy.os.generated.model.TotalUsageInfo;
import io.repsy.os.generated.model.UsageInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class UsageService {

  private final RepoTxService repoTxService;

  public RepoUsageInfo getRepoUsageInfo(final String repoName, final RepoType repoType) {

    final var repo = this.repoTxService.requireRepo(repoName, repoType);

    final var diskUsed = this.createUsageInfo(repo.getDiskUsage());

    return RepoUsageInfo.builder().diskUsed(diskUsed).build();
  }

  public TotalUsageInfo getTotalUsageInfo() {
    final var diskUsed = this.createUsageInfo(this.repoTxService.getTotalDiskUsage());
    final var reposCount = this.repoTxService.countRepos();

    return TotalUsageInfo.builder().diskUsed(diskUsed).reposCount(reposCount).build();
  }

  private UsageInfo createUsageInfo(final long value) {
    return UsageInfo.builder().value(value).text(humanReadable(value)).build();
  }
}
