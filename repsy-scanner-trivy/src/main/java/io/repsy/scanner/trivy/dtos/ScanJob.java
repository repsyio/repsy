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
package io.repsy.scanner.trivy.dtos;

import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record ScanJob(
    @NonNull String scanId,
    @NonNull ScanJobStatus status,
    @Nullable ScanOutcome result,
    @Nullable String errorMessage,
    @NonNull Instant enqueuedAt,
    @Nullable Instant terminalAt) {

  public static @NonNull ScanJob queued(final @NonNull String scanId) {
    return new ScanJob(scanId, ScanJobStatus.QUEUED, null, null, Instant.now(), null);
  }

  public @NonNull ScanJob toRunning() {
    return new ScanJob(this.scanId, ScanJobStatus.RUNNING, null, null, this.enqueuedAt, null);
  }

  public @NonNull ScanJob toCompleted(final @NonNull ScanOutcome outcome) {
    return new ScanJob(
        this.scanId, ScanJobStatus.COMPLETED, outcome, null, this.enqueuedAt, Instant.now());
  }

  public @NonNull ScanJob toFailed(final @NonNull String message) {
    return new ScanJob(
        this.scanId, ScanJobStatus.FAILED, null, message, this.enqueuedAt, Instant.now());
  }
}
