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
package io.repsy.scanner.trivy.gate;

import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;

/**
 * Bounds how many {@code trivy} subprocesses may run at once. The local trivy vulnerability
 * database (a bbolt file baked into the image) is not safe for concurrent {@code trivy rootfs}
 * invocations, so callers must hold a permit for the duration of the subprocess call.
 *
 * <p>Deliberately provider-agnostic: {@link LocalSemaphoreGate} serializes within a single JVM
 * today; a future Cloud deployment can add a Redis-backed implementation (e.g. {@code
 * RedisDistributedGate}, {@code @Profile("cloud")}) coordinating across multiple instances without
 * any caller-side changes.
 */
public interface TrivyExecutionGate {

  /**
   * Blocks up to {@code timeout} waiting for a permit. Throws {@link
   * GateAcquisitionTimeoutException} if none becomes available in time, so callers never wait
   * indefinitely for a stuck or oversaturated gate.
   */
  @NonNull GatePermit acquire(long timeout, @NonNull TimeUnit unit) throws InterruptedException;

  /** A held permit; releasing it is idempotent-safe via try-with-resources. */
  interface GatePermit extends AutoCloseable {

    @Override
    void close();
  }
}
