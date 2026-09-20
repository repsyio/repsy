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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import io.repsy.os.config.async.MaintenanceTaskExecutorConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbandonedBlobUploadCleanupTask")
class AbandonedBlobUploadCleanupTaskTest {

  @Mock AbandonedBlobUploadCleanupService cleanupService;

  @Test
  @DisplayName("cleanup() runs one pass with the configured TTL")
  void cleanupRunsOnePass() {
    new AbandonedBlobUploadCleanupTask(this.cleanupService).cleanup();

    verify(this.cleanupService).cleanupAbandonedUploads();
  }

  @Test
  @DisplayName("cleanup() is scheduled and runs on the maintenance executor")
  void cleanupIsScheduledOnTheMaintenanceExecutor() throws NoSuchMethodException {
    final var method = AbandonedBlobUploadCleanupTask.class.getMethod("cleanup");

    final var async = AnnotationUtils.findAnnotation(method, Async.class);
    final var scheduled = AnnotationUtils.findAnnotation(method, Scheduled.class);

    assertThat(async).isNotNull();
    assertThat(async.value()).isEqualTo(MaintenanceTaskExecutorConfig.BEAN_NAME);
    assertThat(scheduled).isNotNull();
    assertThat(scheduled.fixedDelayString()).contains("abandoned-upload-cleanup.interval");
    assertThat(scheduled.initialDelayString()).contains("abandoned-upload-cleanup.initial-delay");
  }
}
