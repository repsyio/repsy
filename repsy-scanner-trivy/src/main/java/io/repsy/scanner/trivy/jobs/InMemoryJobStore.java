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
package io.repsy.scanner.trivy.jobs;

import io.repsy.scanner.trivy.dtos.ScanJob;
import io.repsy.scanner.trivy.dtos.ScanJobStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

@Component
public class InMemoryJobStore implements JobStore {

  private static final Set<ScanJobStatus> TERMINAL_STATUSES =
      Set.of(ScanJobStatus.COMPLETED, ScanJobStatus.FAILED);

  private final ConcurrentHashMap<String, ScanJob> jobs = new ConcurrentHashMap<>();

  @Override
  public void putIfAbsent(final @NonNull String scanId, final @NonNull ScanJob job) {
    this.jobs.putIfAbsent(scanId, job);
  }

  @Override
  public @NonNull Optional<ScanJob> get(final @NonNull String scanId) {
    return Optional.ofNullable(this.jobs.get(scanId));
  }

  @Override
  public void update(final @NonNull String scanId, final @NonNull ScanJob updated) {
    this.jobs.put(scanId, updated);
  }

  @Override
  public void removeCompletedBefore(final @NonNull Instant threshold) {
    this.jobs.entrySet().removeIf(entry -> isExpired(entry.getValue(), threshold));
  }

  private static boolean isExpired(final @NonNull ScanJob job, final @NonNull Instant threshold) {
    return TERMINAL_STATUSES.contains(job.status())
        && job.terminalAt() != null
        && job.terminalAt().isBefore(threshold);
  }
}
