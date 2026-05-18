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
package io.repsy.os.server.protocols.docker.shared.layer.services;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.server.protocols.docker.shared.layer.dtos.OrphanLayerInfo;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrphanLayerCleanupService {

  private final @NonNull DockerStorageService dockerStorageService;
  private final @NonNull UsageUpdateService usageUpdateService;

  @Async
  public void cleanupBlobs(
      final @NonNull UUID repoId, final @NonNull List<OrphanLayerInfo> orphans) {

    var freedBytes = 0L;

    for (final var orphan : orphans) {
      this.dockerStorageService.deleteBlob(repoId, orphan.digest());
      freedBytes += orphan.size();
    }

    if (freedBytes > 0) {
      final var usages = BaseUsages.builder().diskUsage(-1L * freedBytes).build();
      this.usageUpdateService.updateUsage(new UsageChangedInfo(repoId, usages));
    }
  }
}
