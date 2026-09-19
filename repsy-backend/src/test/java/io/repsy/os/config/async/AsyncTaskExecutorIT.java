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

import io.repsy.os.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Pins which executor an {@code @Async} method without a qualifier runs on (RPS-965).
 *
 * <p>{@code ScanTaskExecutorConfig} registers an {@code Executor} bean, which makes Spring Boot
 * skip its default executor, so unqualified {@code @Async} methods ({@code
 * UsageUpdateService.updateUsage}, the per-protocol auth listeners, ...) fell back to a {@code
 * SimpleAsyncTaskExecutor}: a new, unpooled thread per task. They now run on the bounded {@code
 * applicationTaskExecutor} configured under {@code spring.task.execution}.
 *
 * <p>The probe is handed to the context's own post-processors instead of being registered as a
 * bean, so the tests use the application's real {@code @Async} wiring without a context of their
 * own. They never touch the database.
 */
@DisplayName("Default @Async executor")
class AsyncTaskExecutorIT extends AbstractIntegrationTest {

  private static final long TIMEOUT_SECONDS = 10;

  /** Two threads make up the pool until its queue is full, four is the most it ever grows to. */
  private static final int CORE_POOL_SIZE = 2;

  private static final int MAX_POOL_SIZE = 4;
  private static final int QUEUE_CAPACITY = 500;

  public static class Probe {

    @Async
    public CompletableFuture<String> unqualified() {
      return CompletableFuture.completedFuture(Thread.currentThread().getName());
    }

    @Async
    public CompletableFuture<String> unqualifiedAfter(final CountDownLatch gate)
        throws InterruptedException {
      gate.await();
      return CompletableFuture.completedFuture(Thread.currentThread().getName());
    }

    @Async("scanTaskExecutor")
    public CompletableFuture<String> scan() {
      return CompletableFuture.completedFuture(Thread.currentThread().getName());
    }
  }

  @Autowired private ApplicationContext context;
  @Autowired private ThreadPoolTaskExecutor applicationTaskExecutor;

  private Probe probe() {
    return (Probe)
        this.context
            .getAutowireCapableBeanFactory()
            .applyBeanPostProcessorsAfterInitialization(new Probe(), "asyncProbe");
  }

  private static String await(final CompletableFuture<String> future)
      throws ExecutionException, InterruptedException, TimeoutException {
    return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  @Test
  @DisplayName("an unqualified @Async method runs on the named application pool")
  void unqualifiedAsyncRunsOnApplicationPool() throws Exception {
    final var threadName = await(this.probe().unqualified());

    assertThat(threadName)
        .startsWith("async-")
        .isNotEqualTo(Thread.currentThread().getName())
        .doesNotStartWith("SimpleAsyncTaskExecutor");
  }

  @Test
  @DisplayName("a qualified @Async method still runs on the scan executor")
  void qualifiedAsyncKeepsScanExecutor() throws Exception {
    assertThat(await(this.probe().scan())).startsWith("vuln-scan-");
  }

  @Test
  @DisplayName("the pool is bounded and runs overflow work on the caller instead of dropping it")
  void poolIsBoundedWithCallerRunsRejection() {
    assertThat(this.applicationTaskExecutor.getCorePoolSize()).isEqualTo(CORE_POOL_SIZE);
    assertThat(this.applicationTaskExecutor.getMaxPoolSize()).isEqualTo(MAX_POOL_SIZE);
    assertThat(this.applicationTaskExecutor.getQueueCapacity()).isEqualTo(QUEUE_CAPACITY);
    assertThat(this.applicationTaskExecutor.getThreadPoolExecutor().getRejectedExecutionHandler())
        .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
  }

  @Test
  @DisplayName("a burst of tasks is served by a handful of threads, not one thread per task")
  void burstDoesNotStartAThreadPerTask() throws Exception {
    final var probe = this.probe();
    final var gate = new CountDownLatch(1);
    final var futures = new ArrayList<CompletableFuture<String>>();
    for (var i = 0; i < 50; i++) {
      futures.add(probe.unqualifiedAfter(gate));
    }

    gate.countDown();
    final var threadNames = awaitAll(futures);

    assertThat(threadNames).allMatch(name -> name.startsWith("async-"));
    assertThat(threadNames).hasSizeLessThanOrEqualTo(MAX_POOL_SIZE);
  }

  private static Set<String> awaitAll(final List<CompletableFuture<String>> futures)
      throws ExecutionException, InterruptedException, TimeoutException {
    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    return futures.stream().map(CompletableFuture::join).collect(Collectors.toSet());
  }
}
