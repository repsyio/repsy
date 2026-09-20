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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import io.repsy.libs.storage.gateway.filesystem.service.FileSystemStorageStrategy;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.docker.shared.layer.dtos.OrphanLayerInfo;
import io.repsy.os.server.protocols.docker.shared.layer.services.OrphanLayerCleanupService;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Pins which executor the long-running {@code @Async} maintenance jobs run on (RPS-1030): {@code
 * OrphanLayerCleanupService.cleanupBlobs} and {@code FileSystemStorageStrategy.clearTrash} use the
 * {@code maintenanceTaskExecutor}, so they cannot hold up the default pool that {@code
 * AsyncTaskExecutorIT} covers.
 *
 * <p>Like that class, the tests hand their subjects to the context's own post-processors instead of
 * registering them as beans, so the application's real {@code @Async} wiring applies without a
 * context of their own. The collaborators are mocks or a temporary directory: nothing touches the
 * database.
 */
@DisplayName("Maintenance @Async executor")
class MaintenanceTaskExecutorIT extends AbstractIntegrationTest {

  private static final long TIMEOUT_SECONDS = 10;
  private static final int POOL_SIZE = 2;
  private static final int QUEUE_CAPACITY = 100;

  public static class Probe {

    @Async
    public CompletableFuture<String> unqualified() {
      return CompletableFuture.completedFuture(Thread.currentThread().getName());
    }

    @Async(MaintenanceTaskExecutorConfig.BEAN_NAME)
    public CompletableFuture<String> maintenanceAfter(final CountDownLatch gate)
        throws InterruptedException {
      gate.await();
      return CompletableFuture.completedFuture(Thread.currentThread().getName());
    }
  }

  @Autowired private ApplicationContext context;

  @Autowired
  @Qualifier(MaintenanceTaskExecutorConfig.BEAN_NAME)
  private ThreadPoolTaskExecutor maintenanceTaskExecutor;

  @TempDir private Path tempDir;

  @SuppressWarnings("unchecked")
  private <T> T withAsyncWiring(final T bean, final String beanName) {
    return (T)
        this.context
            .getAutowireCapableBeanFactory()
            .applyBeanPostProcessorsAfterInitialization(bean, beanName);
  }

  @Test
  @DisplayName(
      "the pool is small and fixed, and runs overflow work on the caller instead of dropping it")
  void poolIsBoundedWithCallerRunsRejection() {
    assertThat(this.maintenanceTaskExecutor.getCorePoolSize()).isEqualTo(POOL_SIZE);
    assertThat(this.maintenanceTaskExecutor.getMaxPoolSize()).isEqualTo(POOL_SIZE);
    assertThat(this.maintenanceTaskExecutor.getQueueCapacity()).isEqualTo(QUEUE_CAPACITY);
    assertThat(this.maintenanceTaskExecutor.getThreadNamePrefix()).isEqualTo("maintenance-");
    assertThat(this.maintenanceTaskExecutor.getThreadPoolExecutor().getRejectedExecutionHandler())
        .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
  }

  @Test
  @DisplayName("OrphanLayerCleanupService.cleanupBlobs runs on the maintenance executor")
  void cleanupBlobsRunsOnMaintenanceExecutor() throws Exception {
    final var repoId = UUID.randomUUID();
    final var deletedOn = new AtomicReference<String>();
    final var finished = new CountDownLatch(1);
    final var dockerStorageService = mock(DockerStorageService.class);
    final var usageUpdateService = mock(UsageUpdateService.class);
    doAnswer(
            invocation -> {
              deletedOn.set(Thread.currentThread().getName());
              finished.countDown();
              return null;
            })
        .when(dockerStorageService)
        .deleteBlob(eq(repoId), eq("sha256:orphan"));
    final var service =
        this.withAsyncWiring(
            new OrphanLayerCleanupService(dockerStorageService, usageUpdateService),
            "orphanLayerCleanupServiceProbe");

    service.cleanupBlobs(repoId, List.of(new OrphanLayerInfo("sha256:orphan", 1024L)));

    assertThat(finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    assertThat(deletedOn.get())
        .startsWith("maintenance-")
        .isNotEqualTo(Thread.currentThread().getName());
  }

  @Test
  @DisplayName("FileSystemStorageStrategy.clearTrash is qualified with the maintenance executor")
  void clearTrashIsQualifiedWithTheMaintenanceExecutor() throws Exception {
    final var async =
        AnnotationUtils.findAnnotation(
            FileSystemStorageStrategy.class.getMethod("clearTrash"), Async.class);

    assertThat(async).isNotNull();
    assertThat(async.value()).isEqualTo(MaintenanceTaskExecutorConfig.BEAN_NAME);
  }

  @Test
  @DisplayName("FileSystemStorageStrategy.clearTrash empties the expired trash on that executor")
  void clearTrashRunsOnMaintenanceExecutor() throws Exception {
    final var trashPath = this.tempDir.resolve("trash");
    final var expiredDir =
        Files.createDirectories(
            trashPath.resolve(LocalDate.now(ZoneOffset.UTC).minusDays(2).toString()));
    final var strategy =
        this.withAsyncWiring(
            new FileSystemStorageStrategy(
                this.tempDir.resolve("base").toString(), trashPath.toString(), Duration.ofDays(1)),
            "fileSystemStorageStrategyProbe");
    final var completedBefore =
        this.maintenanceTaskExecutor.getThreadPoolExecutor().getCompletedTaskCount();

    strategy.clearTrash();

    final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    while (Files.exists(expiredDir) && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertThat(expiredDir).doesNotExist();
    while (this.maintenanceTaskExecutor.getThreadPoolExecutor().getCompletedTaskCount()
            == completedBefore
        && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertThat(this.maintenanceTaskExecutor.getThreadPoolExecutor().getCompletedTaskCount())
        .isGreaterThan(completedBefore);
  }

  @Test
  @DisplayName("busy maintenance workers do not delay the default executor")
  void busyMaintenanceWorkersDoNotDelayDefaultExecutor() throws Exception {
    final var probe = this.withAsyncWiring(new Probe(), "maintenanceProbe");
    final var gate = new CountDownLatch(1);
    try {
      final var busy = new ArrayList<CompletableFuture<String>>();
      for (var i = 0; i < POOL_SIZE + 5; i++) {
        busy.add(probe.maintenanceAfter(gate));
      }

      final var threadName = probe.unqualified().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

      assertThat(threadName).startsWith("async-");
      assertThat(busy).noneMatch(CompletableFuture::isDone);
    } finally {
      gate.countDown();
    }
  }

  @Test
  @DisplayName("an unqualified @Async method still runs on the default pool")
  void unqualifiedAsyncStillRunsOnDefaultPool() throws Exception {
    final var probe = this.withAsyncWiring(new Probe(), "defaultProbe");

    assertThat(probe.unqualified().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).startsWith("async-");
  }
}
