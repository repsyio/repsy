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

import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * In-process {@link TrivyExecutionGate} backed by a single-permit {@link Semaphore}. Serializes
 * {@code trivy rootfs} calls within this JVM only — sufficient for a single-instance deployment,
 * but does not coordinate across multiple instances (see {@link TrivyExecutionGate} for the
 * Redis-backed follow-up).
 */
@Component
public class LocalSemaphoreGate implements TrivyExecutionGate {

  private final Semaphore semaphore = new Semaphore(1);

  @Override
  public @NonNull GatePermit acquire(final long timeout, final @NonNull TimeUnit unit)
      throws InterruptedException {

    final var acquired = this.semaphore.tryAcquire(timeout, unit);

    if (!acquired) {
      throw new GateAcquisitionTimeoutException(
          "Timed out after "
              + timeout
              + " "
              + unit.name().toLowerCase(Locale.ROOT)
              + " waiting for a trivy execution slot");
    }

    return this.semaphore::release;
  }
}
