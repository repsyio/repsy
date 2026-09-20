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
package io.repsy.os.server.protocols.shared.services;

import io.repsy.os.config.async.MaintenanceTaskExecutorConfig;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers {@link AbandonedBlobUploadCleanupService} on a schedule. The pass walks the blob
 * directory of every Docker and Helm repo, so it runs on the {@code maintenanceTaskExecutor}
 * instead of holding the single scheduler thread that the other periodic jobs share.
 *
 * <p>The first pass waits {@code initial-delay} after startup so it does not compete with the
 * application coming up. Set {@code repsy.storage.abandoned-upload-cleanup.enabled} to {@code
 * false} to switch the job off.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "repsy.storage.abandoned-upload-cleanup.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AbandonedBlobUploadCleanupTask {

  private final @NonNull AbandonedBlobUploadCleanupService cleanupService;

  @Async(MaintenanceTaskExecutorConfig.BEAN_NAME)
  @Scheduled(
      initialDelayString = "${repsy.storage.abandoned-upload-cleanup.initial-delay:PT10M}",
      fixedDelayString = "${repsy.storage.abandoned-upload-cleanup.interval:PT1H}")
  public void cleanup() {

    this.cleanupService.cleanupAbandonedUploads();
  }
}
