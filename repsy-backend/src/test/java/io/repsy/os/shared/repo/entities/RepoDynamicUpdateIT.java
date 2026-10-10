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
package io.repsy.os.shared.repo.entities;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A full-row save of a {@link Repo} must not overwrite a usage increment that committed between the
 * load and the flush (RPS-2116).
 *
 * <p>Without {@code @DynamicUpdate} Hibernate writes every column on a dirty entity, including the
 * stale {@code disk_usage} read at load time, so the increment is lost. The test runs a real race:
 * one thread holds a loaded entity open while another commits the increment, then the first one
 * saves an unrelated field. It commits its own rows, so the class is {@link
 * Propagation#NOT_SUPPORTED NOT_SUPPORTED}.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Repo full-row save versus a concurrent usage increment")
class RepoDynamicUpdateIT extends AbstractIntegrationTest {

  private static final long TIMEOUT_SECONDS = 20;

  @Autowired private PlatformTransactionManager transactionManager;

  private UUID repoId;

  @AfterEach
  void cleanUp() {
    if (this.repoId != null) {
      this.repoRepository.deleteById(this.repoId);
    }
  }

  @Test
  @DisplayName("keeps a usage increment committed while another transaction saves the repo")
  void saveOfUnrelatedFieldKeepsConcurrentIncrement() throws Exception {
    final var repo = new Repo();
    repo.setName(uniqueRepoName("dynupd"));
    repo.setType(RepoType.MAVEN);
    repo.setAllowOverride(true);
    this.repoId = this.repoRepository.saveAndFlush(repo).getId();

    final var template = new TransactionTemplate(this.transactionManager);
    final var loaded = new CountDownLatch(1);
    final var incremented = new CountDownLatch(1);
    final ExecutorService executor = Executors.newSingleThreadExecutor();

    try {
      // Thread A: loads the repo (disk usage 0), waits for the increment, then saves a description.
      final Future<?> saver =
          executor.submit(
              () ->
                  template.executeWithoutResult(
                      status -> {
                        final var entity = this.repoRepository.findById(this.repoId).orElseThrow();
                        loaded.countDown();
                        await(incremented);
                        entity.setDescription("changed by the profile save");
                        this.repoRepository.save(entity);
                      }));

      // Thread B (the test thread): commits the increment while A holds the stale entity.
      await(loaded);
      final var updated =
          template.execute(status -> this.repoRepository.updateDiskUsage(this.repoId, 5));
      assertThat(updated).isEqualTo(1);
      incremented.countDown();

      saver.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } finally {
      incremented.countDown();
      executor.shutdownNow();
    }

    final var after = this.repoRepository.findById(this.repoId).orElseThrow();
    assertThat(after.getDescription()).isEqualTo("changed by the profile save");
    assertThat(after.getDiskUsage()).isEqualTo(5);
  }

  private static void await(final CountDownLatch latch) {
    try {
      assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
