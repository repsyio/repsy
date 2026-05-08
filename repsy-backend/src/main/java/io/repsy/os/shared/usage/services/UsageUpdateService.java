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
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class UsageUpdateService {

  private static final int DISK_USAGE_INDEX = 0;
  private static final int INBOUND_TRAFFIC_INDEX = 1;
  private static final int OUTBOUND_TRAFFIC_INDEX = 2;

  private final @NonNull RepoTxService repoTxService;

  private final ConcurrentHashMap<UUID, long[]> pendingUpdates = new ConcurrentHashMap<>();

  public void updateUsage(final @NonNull UsageChangedInfo info) {
    final var usages = info.usages();
    final long diskDiff = usages.getDiskUsage();
    final long inboundDiff = usages.getInboundTrafficUsage();
    final long outboundDiff = usages.getOutboundTrafficUsage();

    pendingUpdates.merge(
        info.repoId(),
        new long[] {diskDiff, inboundDiff, outboundDiff},
        (existing, delta) ->
            new long[] {
              existing[DISK_USAGE_INDEX] + delta[DISK_USAGE_INDEX],
              existing[INBOUND_TRAFFIC_INDEX] + delta[INBOUND_TRAFFIC_INDEX],
              existing[OUTBOUND_TRAFFIC_INDEX] + delta[OUTBOUND_TRAFFIC_INDEX]
            });
  }

  @Scheduled(fixedDelayString = "${os.app.usage-flush-interval-ms:5000}")
  public void flushPendingUpdates() {
    if (pendingUpdates.isEmpty()) {
      return;
    }

    final var repoIds = new ArrayList<>(pendingUpdates.keySet());
    for (final UUID repoId : repoIds) {
      final long[] delta = pendingUpdates.remove(repoId);
      if (delta == null) {
        continue;
      }
      try {
        this.repoTxService.updateUsages(
            repoId,
            delta[DISK_USAGE_INDEX],
            delta[INBOUND_TRAFFIC_INDEX],
            delta[OUTBOUND_TRAFFIC_INDEX]);
      } catch (final Exception e) {
        log.error("Failed to flush usage update for repo {}: {}", repoId, e.getMessage());
      }
    }
  }
}
