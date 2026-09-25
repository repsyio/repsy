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
package io.repsy.os.server.protocols.golang.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.os.H2IntegrationTest;
import io.repsy.os.server.protocols.golang.shared.go_module.repositories.GoModuleRepository;
import io.repsy.os.server.protocols.golang.shared.go_module.services.GoModuleServiceImpl;
import io.repsy.os.server.protocols.golang.shared.storage.services.GolangStorageService;
import io.repsy.os.server.protocols.golang.ui.facades.GolangApiFacade;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The H2 counterpart of {@link GolangLastVersionRemovalIT} (RPS-1288): on the embedded database
 * too, the delete of a module's last version removes the module, and it takes turns with a publish
 * of the same module. The locks the two take (the module row for update and for share) have to hold
 * on H2 as they do on PostgreSQL.
 *
 * <p>Like the PostgreSQL test it commits, so it runs without the inherited test transaction and
 * deletes the repos it created. It has no HTTP cases: the H2 tests avoid MockMvc so that they share
 * one application context, and it holds no spy, for the same reason. A publish is held inside its
 * transaction by its own files writer, and a delete is held by an open transaction that took the
 * module row's lock the way the delete does. An operation counts as blocked when it is still
 * running after {@link #STILL_BLOCKED}, which is shorter than H2's lock timeout.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Deleting the last Go module version on the embedded H2 database (RPS-1288)")
class H2GolangLastVersionRemovalIT extends H2IntegrationTest {

  private static final Duration STILL_BLOCKED = Duration.ofSeconds(1);
  private static final int TIMEOUT_SECONDS = 20;

  @Autowired private RepoTxService repoTxService;
  @Autowired private GoModuleServiceImpl goModuleService;
  @Autowired private GoModuleRepository goModuleRepository;
  @Autowired private GolangApiFacade golangApiFacade;
  @Autowired private GolangStorageService golangStorageService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private ExecutorService executor;

  @BeforeEach
  void startExecutor() {
    this.executor = Executors.newFixedThreadPool(2);
  }

  @AfterEach
  void cleanUp() {
    this.executor.shutdownNow();
    // Every table that references a repo cascades on delete, so this takes the modules with it.
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from \"public\".\"repo\" where \"id\" = ?", id));
    this.createdRepoIds.clear();
  }

  private RepoInfo goRepo() {
    final var repo =
        this.repoTxService.createRepo(
            "h2go" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
            RepoType.GOLANG,
            false,
            null);
    this.createdRepoIds.add(repo.getId());
    this.golangStorageService.createRepo(repo.getStorageKey());

    return repo;
  }

  private static String modulePath() {
    return "example.com/h2rm" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  /**
   * Publishes the version like the protocol does: its row first, its files inside the transaction.
   */
  private void publish(final RepoInfo repo, final String modulePath, final String version)
      throws IOException {
    this.publish(repo, modulePath, version, () -> {});
  }

  private void publish(
      final RepoInfo repo, final String modulePath, final String version, final Runnable hold)
      throws IOException {
    this.goModuleService.publishModule(
        repo,
        modulePath,
        version,
        "1.21",
        "h1:mod",
        "h1:zip",
        () -> {
          hold.run();
          return this.storeFiles(repo, modulePath, version);
        });
  }

  private BaseUsages storeFiles(
      final RepoInfo repo, final String modulePath, final String version) {
    long bytes = 0L;
    for (final var extension : List.of(".info", ".mod", ".zip")) {
      final var content = (modulePath + version + extension).getBytes(StandardCharsets.UTF_8);
      bytes +=
          this.golangStorageService
              .writeInputStreamToPath(
                  StoragePath.of(
                      repo.getStorageKey(), "/" + modulePath + "/@v/" + version + extension),
                  new ByteArrayInputStream(content),
                  repo.getName())
              .getDiskUsage();
    }

    return BaseUsages.ofDisk(bytes);
  }

  private List<String> storedFiles(final RepoInfo repo, final String modulePath) {
    return this.golangStorageService
        .listDirectory(StoragePath.of(repo.getStorageKey(), "/" + modulePath + "/@v"))
        .stream()
        .map(item -> item.getName())
        .sorted()
        .toList();
  }

  private static List<String> filesOf(final String version) {
    return List.of(version + ".info", version + ".mod", version + ".zip");
  }

  private int moduleCount(final RepoInfo repo, final String modulePath) {
    final var count =
        this.jdbcTemplate.queryForObject(
            "select count(*) from \"public\".\"go_module\" where \"repo_id\" = ? and \"module_path\" = ?",
            Integer.class,
            repo.getId(),
            modulePath);

    return count == null ? 0 : count;
  }

  private List<String> versionsOf(final RepoInfo repo, final String modulePath) {
    return this.jdbcTemplate.queryForList(
        """
        select v."version" from "public"."go_module_version" v
          join "public"."go_module" m on m."id" = v."module_id"
        where m."repo_id" = ? and m."module_path" = ?
        order by v."version"
        """,
        String.class,
        repo.getId(),
        modulePath);
  }

  /** Starts the operation and waits until it has run for {@link #STILL_BLOCKED} without ending. */
  private <T> Future<T> startBlocked(final java.util.concurrent.Callable<T> operation)
      throws InterruptedException {
    final var started = new CountDownLatch(1);
    final var pending =
        this.executor.submit(
            () -> {
              started.countDown();
              return operation.call();
            });

    assertThat(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    await()
        .during(STILL_BLOCKED)
        .atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .until(() -> !pending.isDone());

    return pending;
  }

  @Test
  @DisplayName("deleting the last version removes the module and its files; a publish recreates it")
  void lastVersionRemovesTheModule() throws Exception {
    final var repo = this.goRepo();
    final var module = modulePath();
    this.publish(repo, module, "v1.0.0");
    this.publish(repo, module, "v1.1.0");

    final var first = this.golangApiFacade.deleteModuleVersion(repo, module, "v1.0.0");

    assertThat(first.getDiskUsage()).as("the bytes given back").isNegative();
    assertThat(this.moduleCount(repo, module)).isOne();
    assertThat(this.storedFiles(repo, module)).isEqualTo(filesOf("v1.1.0"));

    this.golangApiFacade.deleteModuleVersion(repo, module, "v1.1.0");

    assertThat(this.moduleCount(repo, module)).isZero();
    assertThat(this.storedFiles(repo, module)).isEmpty();

    this.publish(repo, module, "v1.0.0");

    assertThat(this.moduleCount(repo, module)).isOne();
    assertThat(this.versionsOf(repo, module)).containsExactly("v1.0.0");
    assertThat(this.storedFiles(repo, module)).isEqualTo(filesOf("v1.0.0"));
  }

  @Test
  @DisplayName("a delete of the last version waits for a publish, and the module keeps its new one")
  void deleteWaitsForAPublish() throws Exception {
    final var repo = this.goRepo();
    final var module = modulePath();
    this.publish(repo, module, "v1.0.0");
    final var writing = new CountDownLatch(1);
    final var release = new CountDownLatch(1);

    final var publishing =
        this.executor.submit(
            () -> {
              this.publish(
                  repo,
                  module,
                  "v1.1.0",
                  () -> {
                    writing.countDown();
                    try {
                      release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    } catch (final InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                  });
              return null;
            });
    assertThat(writing.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

    final var deleting =
        this.startBlocked(() -> this.golangApiFacade.deleteModuleVersion(repo, module, "v1.0.0"));
    release.countDown();

    publishing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    deleting.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    assertThat(this.moduleCount(repo, module)).isOne();
    assertThat(this.versionsOf(repo, module)).containsExactly("v1.1.0");
    assertThat(this.storedFiles(repo, module)).isEqualTo(filesOf("v1.1.0"));
  }

  @Test
  @DisplayName("a publish waits for a delete that removed the module, and creates it again")
  void publishWaitsForTheDelete() throws Exception {
    final var repo = this.goRepo();
    final var module = modulePath();
    this.publish(repo, module, "v1.0.0");
    final var holder = new TransactionTemplate(this.transactionManager);

    // The transaction locks the module row for update and deletes it, as the delete of the last
    // version does, and stays open.
    final var publishing =
        holder.execute(
            status -> {
              final var locked =
                  this.goModuleRepository
                      .findLockedByRepoIdAndModulePath(repo.getStorageKey(), module)
                      .orElseThrow();
              this.goModuleRepository.delete(locked);
              this.goModuleRepository.flush();

              try {
                return this.startBlocked(
                    () -> {
                      this.publish(repo, module, "v1.1.0");
                      return null;
                    });
              } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
              }
            });

    publishing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    assertThat(this.moduleCount(repo, module)).isOne();
    assertThat(this.versionsOf(repo, module)).containsExactly("v1.1.0");
    assertThat(this.storedFiles(repo, module)).contains(filesOf("v1.1.0").toArray(String[]::new));
  }
}
