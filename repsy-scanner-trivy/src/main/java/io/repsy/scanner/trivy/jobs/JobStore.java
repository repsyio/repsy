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
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.NonNull;

public interface JobStore {

  // No-op if scanId is already present, so a retried submit never re-enqueues duplicate work.
  void putIfAbsent(@NonNull String scanId, @NonNull ScanJob job);

  @NonNull Optional<ScanJob> get(@NonNull String scanId);

  void update(@NonNull String scanId, @NonNull ScanJob updated);

  void removeCompletedBefore(@NonNull Instant threshold);
}
