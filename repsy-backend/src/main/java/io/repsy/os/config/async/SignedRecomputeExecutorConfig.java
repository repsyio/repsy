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

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The executor of the recomputation of {@code signed} that follows a toggle of a Maven repo's
 * {@code pgpVerifyAllSignaturesEnabled} setting ({@code SignedRecomputeService}, RPS-1323). It is
 * not the {@code maintenanceTaskExecutor}: that one runs a job on the calling thread when its queue
 * is full, which for a recompute would be the request that committed the toggle, and a recompute
 * walks and verifies every version of a repo.
 *
 * <p>A full queue rejects the job instead ({@link ThreadPoolExecutor.AbortPolicy}); the service
 * logs it and the toggle can be repeated. That takes as many repos with a waiting recompute as the
 * queue holds, and the service runs a repo once however many toggles are waiting.
 */
@Configuration
public class SignedRecomputeExecutorConfig {

  public static final String BEAN_NAME = "signedRecomputeExecutor";

  /** One at a time: a run is I/O bound, holds a row lock per version and is rare. */
  private static final int POOL_SIZE = 1;

  private static final int QUEUE_CAPACITY = 50;

  private static final int AWAIT_TERMINATION_SECONDS = 30;

  @Bean(BEAN_NAME)
  public @NonNull Executor signedRecomputeExecutor() {

    final var executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(POOL_SIZE);
    executor.setMaxPoolSize(POOL_SIZE);
    executor.setQueueCapacity(QUEUE_CAPACITY);
    executor.setThreadNamePrefix("signed-recompute-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS);

    return executor;
  }
}
