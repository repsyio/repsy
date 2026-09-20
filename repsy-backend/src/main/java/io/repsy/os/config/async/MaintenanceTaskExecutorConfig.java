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

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The executor of long-running {@code @Async} maintenance jobs: deleting the orphaned blobs of a
 * Docker repo ({@code OrphanLayerCleanupService.cleanupBlobs}) and emptying the expired storage
 * trash ({@code FileSystemStorageStrategy.clearTrash}). Both walk and delete many files, so they
 * can run for minutes.
 *
 * <p>They used to share the small default pool ({@code AsyncTaskExecutorConfig}) with the short,
 * latency-sensitive tasks ({@code UsageUpdateService.updateUsage}, the login and auth listeners,
 * ...). A few maintenance jobs at once held every worker there, and disk usage and last-login
 * updates queued up behind them. A method opts in with {@code @Async(MaintenanceTaskExecutorConfig
 * .BEAN_NAME)}; {@code FileSystemStorageStrategy} lives in a library that cannot see this class, so
 * it repeats the name as a literal and {@code MaintenanceTaskExecutorIT} pins the two together.
 */
@Configuration
public class MaintenanceTaskExecutorConfig {

  public static final String BEAN_NAME = "maintenanceTaskExecutor";

  /**
   * Fixed size: a job is I/O bound and rare, two of them at once is plenty, and a queue that is
   * never full means the pool never grows past its core.
   */
  private static final int POOL_SIZE = 2;

  /** A queued job is only a repo id and a list of digests, so a deep queue costs almost nothing. */
  private static final int QUEUE_CAPACITY = 100;

  private static final Duration AWAIT_TERMINATION_PERIOD = Duration.ofSeconds(30);

  /**
   * Lets the submitting thread run a job itself when the queue is full. A job must not be dropped:
   * {@code cleanupBlobs} starts after the orphaned layer rows are already deleted, so a rejected
   * job would leave its blobs on disk with nothing left that points at them. That only happens
   * after {@value #QUEUE_CAPACITY} jobs are waiting, far above what a manual "delete orphan layers"
   * action produces, so the slow work reaching a request thread is the lesser evil.
   */
  @Bean(BEAN_NAME)
  public @NonNull Executor maintenanceTaskExecutor() {

    final var executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(POOL_SIZE);
    executor.setMaxPoolSize(POOL_SIZE);
    executor.setQueueCapacity(QUEUE_CAPACITY);
    executor.setThreadNamePrefix("maintenance-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds((int) AWAIT_TERMINATION_PERIOD.toSeconds());

    return executor;
  }
}
