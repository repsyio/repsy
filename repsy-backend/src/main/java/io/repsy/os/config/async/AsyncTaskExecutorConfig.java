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

import java.util.concurrent.ThreadPoolExecutor;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.task.ThreadPoolTaskExecutorCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Settings of the default {@code @Async} executor that {@code spring.task.execution.*} cannot
 * express.
 *
 * <p>The executor itself is Spring Boot's {@code applicationTaskExecutor}. {@code
 * ScanTaskExecutorConfig} registers another {@link java.util.concurrent.Executor} bean, which makes
 * Boot skip it, so {@code spring.task.execution.mode=force} in {@code application.yml} keeps it and
 * sizes the pool. Without it every unqualified {@code @Async} method ({@code
 * UsageUpdateService.updateUsage}, the per-protocol auth listeners, ...) ran on a new thread per
 * task, unbounded, each taking a database connection.
 */
@Configuration
public class AsyncTaskExecutorConfig {

  /**
   * Makes the submitting thread run a task itself when the pool and its queue are full. The work is
   * bookkeeping and default repository seeding, which must not be dropped, and running it on the
   * caller also slows down whoever floods the queue.
   */
  @Bean
  public @NonNull ThreadPoolTaskExecutorCustomizer callerRunsRejectionPolicyCustomizer() {
    return executor ->
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
  }
}
