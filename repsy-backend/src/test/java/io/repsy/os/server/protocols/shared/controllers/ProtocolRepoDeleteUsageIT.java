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
package io.repsy.os.server.protocols.shared.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.auth.utils.PasswordGeneratorUtil;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * End-to-end coverage of the disk usage bookkeeping around {@code DELETE /api/repos/{repoName}}
 * with the real {@link UsageUpdateService} (RPS-908).
 *
 * <p>{@code ProtocolRepoControllerIT} is {@code @Transactional} and rolls back, so an
 * {@code @Async} method on another thread could never see its rows and it has to mock {@link
 * UsageUpdateService}. This class is therefore {@link Propagation#NOT_SUPPORTED NOT_SUPPORTED}:
 * every fixture is committed for real, and {@link #cleanUp()} deletes what a test created. The
 * database is shared with every other IT class in the JVM, so the totals are asserted relative to a
 * baseline taken at the start of each test rather than as absolute values.
 *
 * <p>The delete used to submit an {@code @Async} usage update for the repo and then delete its row
 * straight away. The update looks the row up by id, so when it ran after the delete it failed with
 * {@code repoNotFound} on the async thread, where {@code SimpleAsyncUncaughtExceptionHandler} only
 * logs it at ERROR. The tests capture every ERROR logged while they run and require none.
 *
 * <p>Left alone, the async update nearly always reaches the row first (and its {@code UPDATE} lock
 * makes the delete wait), so the race only shows when the task is delayed. {@link GatedExecutor}
 * makes that delay deterministic: it replaces the default {@code @Async} executor (through an
 * {@link AsyncConfigurer}, which Spring Boot's {@code applicationTaskExecutor} defers to) and holds
 * submitted tasks while it is closed. Registering it gives this class its own Spring context.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(ProtocolRepoDeleteUsageIT.GatedExecutorConfig.class)
@DisplayName("ProtocolRepoController DELETE /api/repos/{repoName} usage bookkeeping")
class ProtocolRepoDeleteUsageIT extends AbstractIntegrationTest {

  /**
   * The default {@code @Async} executor for this class: runs every task on a new thread, except
   * that while {@link #close() closed} it holds the tasks back until {@link #open()}.
   */
  static final class GatedExecutor implements TaskExecutor {

    private final SimpleAsyncTaskExecutor delegate = new SimpleAsyncTaskExecutor("gated-async-");
    private final List<Runnable> held = new ArrayList<>();
    private boolean closed;

    @Override
    public synchronized void execute(final Runnable task) {
      if (this.closed) {
        this.held.add(task);
      } else {
        this.delegate.execute(task);
      }
    }

    synchronized void close() {
      this.closed = true;
    }

    synchronized void open() {
      this.closed = false;
      this.held.forEach(this.delegate::execute);
      this.held.clear();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class GatedExecutorConfig {

    @Bean
    GatedExecutor gatedExecutor() {
      return new GatedExecutor();
    }

    @Bean
    AsyncConfigurer gatedAsyncConfigurer(final GatedExecutor gatedExecutor) {
      return new AsyncConfigurer() {
        @Override
        public Executor getAsyncExecutor() {
          return gatedExecutor;
        }
      };
    }
  }

  private static final String USAGES_PATH = "/api/usages";
  private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(10);

  /** How long the tests keep watching for a late ERROR from an async task after the delete. */
  private static final Duration QUIET_PERIOD = Duration.ofSeconds(1);

  @Autowired private UsageUpdateService usageUpdateService;
  @Autowired private GatedExecutor asyncExecutor;

  private final List<UUID> createdUserIds = new ArrayList<>();
  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();

  private String token;

  @BeforeEach
  void setUp() {
    this.logEvents.start();
    rootLogger().addAppender(this.logEvents);
    this.token = this.createCommittedAdmin();
  }

  @AfterEach
  void cleanUp() {
    this.asyncExecutor.open();
    rootLogger().detachAppender(this.logEvents);
    this.logEvents.stop();
    this.createdRepoIds.stream()
        .filter(this.repoRepository::existsById)
        .forEach(this.repoRepository::deleteById);
    this.userRepository.deleteAllById(this.createdUserIds);
  }

  // ---------------------------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------------------------

  private static Logger rootLogger() {
    return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
  }

  /**
   * The base class' {@code createUser} flushes the test transaction, which does not exist here, so
   * commit the user through the service directly.
   */
  private String createCommittedAdmin() {
    final var username = uniqueUsername("deladm");
    final var salt = PasswordGeneratorUtil.generateSalt();
    final var hash = PasswordGeneratorUtil.hashPassword(VALID_PASSWORD, salt);
    final var userInfo = this.userTxService.create(username, UserRole.ADMIN, hash, salt);
    this.createdUserIds.add(userInfo.getId());
    return this.bearerTokenFor(userInfo.getId(), username);
  }

  /** Creates a committed repo through {@code POST /api/repos/{repoType}}: row and storage dir. */
  private Repo createRepo(final RepoType type) throws Exception {
    final var name = uniqueRepoName("del" + type.name().toLowerCase(Locale.ROOT).substring(0, 3));

    this.perform(
            post("/api/repos/" + type.name())
                .header(AUTHORIZATION, this.token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"%s\"}".formatted(name)))
        .andExpect(status().isOk());

    final var repo = this.repoRepository.findByName(name).orElseThrow();
    this.createdRepoIds.add(repo.getId());
    return repo;
  }

  private static void writeFile(final Repo repo, final String relativePath, final String content)
      throws IOException {
    final var file = storageDirOf(repo).resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  /** Records usage through the real {@code @Async} service and waits until it is committed. */
  private void recordUsage(final UUID repoId, final long diskUsageDiff) {
    final var expected = this.diskUsageOf(repoId) + diskUsageDiff;

    this.usageUpdateService.updateUsage(
        new UsageChangedInfo(repoId, BaseUsages.ofDisk(diskUsageDiff)));

    await()
        .atMost(ASYNC_TIMEOUT)
        .untilAsserted(() -> assertThat(this.diskUsageOf(repoId)).isEqualTo(expected));
  }

  private long diskUsageOf(final UUID repoId) {
    return this.repoRepository.findById(repoId).orElseThrow().getDiskUsage();
  }

  private void deleteRepo(final Repo repo) throws Exception {
    expectSuccess(
        this.perform(delete("/api/repos/" + repo.getName()).header(AUTHORIZATION, this.token)),
        "repoDeleted",
        "Repo deleted.");
  }

  private record Totals(long diskUsed, long reposCount) {}

  private Totals totals() throws Exception {
    final var body =
        expectSuccess(
            this.perform(get(USAGES_PATH).header(AUTHORIZATION, this.token)),
            "usageFetched",
            "Usage fetched");

    return new Totals(
        JsonPath.<Number>read(body, "$.data.diskUsed.value").longValue(),
        JsonPath.<Number>read(body, "$.data.reposCount").longValue());
  }

  /**
   * Keeps checking for {@link #QUIET_PERIOD} that nothing, in particular no async task still
   * working on the deleted repo, has logged an ERROR.
   */
  private void assertNoErrorLogged() {
    await()
        .during(QUIET_PERIOD)
        .atMost(QUIET_PERIOD.plus(ASYNC_TIMEOUT))
        .untilAsserted(
            () ->
                assertThat(
                        List.copyOf(this.logEvents.list).stream()
                            .filter(event -> event.getLevel().isGreaterOrEqual(Level.ERROR))
                            .map(
                                event -> event.getThreadName() + " " + event.getFormattedMessage()))
                    .isEmpty());
  }

  // ---------------------------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("drops a repo with content and its usage from the totals, logging no error")
  void deletesRepoWithContent() throws Exception {
    final var baseline = this.totals();
    final var repo = this.createRepo(RepoType.MAVEN);
    writeFile(repo, "com/acme/lib/1.0/lib-1.0.jar", "jar-bytes");
    writeFile(repo, "com/acme/lib/maven-metadata.xml", "<metadata/>");
    writeFile(repo, "root.txt", "root");
    this.recordUsage(repo.getId(), 9 + 11 + 4);
    assertThat(this.totals())
        .isEqualTo(new Totals(baseline.diskUsed() + 24, baseline.reposCount() + 1));

    this.deleteRepo(repo);

    assertThat(this.repoRepository.existsById(repo.getId())).isFalse();
    assertThat(storageDirOf(repo)).doesNotExist();
    assertThat(this.totals()).isEqualTo(baseline);
    this.assertNoErrorLogged();
    assertThat(this.totals()).isEqualTo(baseline);
  }

  @Test
  @DisplayName("stays consistent when the async executor is backed up during the delete")
  void deletesWhileAsyncExecutorIsBackedUp() throws Exception {
    final var baseline = this.totals();
    final var repo = this.createRepo(RepoType.MAVEN);
    writeFile(repo, "com/acme/lib/1.0/lib-1.0.jar", "jar-bytes");
    this.recordUsage(repo.getId(), 9);

    this.asyncExecutor.close();
    this.deleteRepo(repo);
    assertThat(this.repoRepository.existsById(repo.getId())).isFalse();
    this.asyncExecutor.open();

    this.assertNoErrorLogged();
    assertThat(this.totals()).isEqualTo(baseline);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(RepoType.class)
  @DisplayName("drops a repo's recorded usage from the totals for every RepoType, logging no error")
  void deletesRepoOfEveryType(final RepoType type) throws Exception {
    final var baseline = this.totals();
    final var repo = this.createRepo(type);
    this.recordUsage(repo.getId(), 4096);
    assertThat(this.totals())
        .isEqualTo(new Totals(baseline.diskUsed() + 4096, baseline.reposCount() + 1));

    this.deleteRepo(repo);

    assertThat(this.repoRepository.existsById(repo.getId())).isFalse();
    assertThat(this.totals()).isEqualTo(baseline);
    this.assertNoErrorLogged();
  }

  @Test
  @DisplayName("leaves the usage of the other repos untouched")
  void leavesOtherReposUsageAlone() throws Exception {
    final var baseline = this.totals();
    final var victim = this.createRepo(RepoType.NPM);
    final var bystander = this.createRepo(RepoType.NPM);
    this.recordUsage(victim.getId(), 1000);
    this.recordUsage(bystander.getId(), 2000);

    this.deleteRepo(victim);

    this.assertNoErrorLogged();
    assertThat(this.diskUsageOf(bystander.getId())).isEqualTo(2000);
    assertThat(this.totals())
        .isEqualTo(new Totals(baseline.diskUsed() + 2000, baseline.reposCount() + 1));
  }
}
