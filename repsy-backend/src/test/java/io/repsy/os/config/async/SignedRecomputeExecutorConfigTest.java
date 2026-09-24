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
package io.repsy.os.config.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The executor of the recomputation of {@code signed} rejects a job it cannot queue instead of
 * running it on the submitting thread, as the maintenance executor does (RPS-1323).
 */
@DisplayName("Signed recompute executor (RPS-1323)")
class SignedRecomputeExecutorConfigTest {

  @Test
  @DisplayName("a full queue rejects the job and never runs it on the caller")
  void aFullQueueRejectsInsteadOfRunningOnTheCaller() throws Exception {
    final var executor =
        (ThreadPoolTaskExecutor) new SignedRecomputeExecutorConfig().signedRecomputeExecutor();
    executor.initialize();
    final var gate = new CountDownLatch(1);
    final var callerThread = Thread.currentThread();

    try {
      assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
          .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
      assertThat(executor.getCorePoolSize()).isEqualTo(1);
      assertThat(executor.getMaxPoolSize()).isEqualTo(1);
      assertThat(executor.getThreadNamePrefix()).isEqualTo("signed-recompute-");

      // One job running and the queue full.
      executor.execute(() -> awaitQuietly(gate));
      for (var i = 0; i < executor.getQueueCapacity(); i++) {
        executor.execute(() -> awaitQuietly(gate));
      }

      assertThatThrownBy(
              () ->
                  executor.execute(
                      () -> assertThat(Thread.currentThread()).isNotSameAs(callerThread)))
          .isInstanceOf(RejectedExecutionException.class);
    } finally {
      gate.countDown();
      executor.shutdown();
    }
  }

  private static void awaitQuietly(final CountDownLatch gate) {
    try {
      gate.await();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
