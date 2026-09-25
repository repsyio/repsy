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
package io.repsy.os.shared.usage.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link UsageUpdateService} against a real database when an update would take a repo's disk usage
 * below zero (RPS-1028).
 *
 * <p>{@code repo.disk_usage} has a {@code CHECK (disk_usage >= 0)} constraint, so an unclamped
 * {@code UPDATE} that makes it negative fails and rolls the whole {@code @Async} update back, which
 * only shows up as an ERROR from {@code SimpleAsyncUncaughtExceptionHandler}. The service clamps
 * the diff instead and logs the repo and the diff itself.
 *
 * <p>The service is {@code @Async}, so an update runs in a transaction of its own and cannot see
 * the rows of a rolled-back test transaction. The class is therefore {@link
 * Propagation#NOT_SUPPORTED NOT_SUPPORTED}, commits its repos and deletes them in {@link
 * #cleanUp()}.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("UsageUpdateService negative disk usage")
class UsageUpdateServiceIT extends AbstractIntegrationTest {

  private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(10);

  /** How long the tests keep watching for a late ERROR from an async task. */
  private static final Duration QUIET_PERIOD = Duration.ofSeconds(1);

  @Autowired private UsageUpdateService usageUpdateService;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();

  @BeforeEach
  void captureLogs() {
    this.logEvents.start();
    rootLogger().addAppender(this.logEvents);
  }

  @AfterEach
  void cleanUp() {
    rootLogger().detachAppender(this.logEvents);
    this.logEvents.stop();
    this.repoRepository.deleteAllById(this.createdRepoIds);
  }

  private static Logger rootLogger() {
    return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
  }

  private Repo commitRepo() {
    final var repo = new Repo();
    repo.setName(uniqueRepoName("usage"));
    repo.setType(RepoType.MAVEN);
    repo.setAllowOverride(true);

    final var saved = this.repoRepository.saveAndFlush(repo);
    this.createdRepoIds.add(saved.getId());
    return saved;
  }

  private long diskUsageOf(final UUID repoId) {
    return this.repoRepository.findById(repoId).orElseThrow().getDiskUsage();
  }

  private void submit(final UUID repoId, final long diskUsageDiff) {
    this.usageUpdateService.updateUsage(
        new UsageChangedInfo(repoId, BaseUsages.ofDisk(diskUsageDiff)));
  }

  /** Submits an update and waits until the repo's usage is committed as {@code expected}. */
  private void submitAndAwait(final UUID repoId, final long diskUsageDiff, final long expected) {
    this.submit(repoId, diskUsageDiff);

    await()
        .atMost(ASYNC_TIMEOUT)
        .untilAsserted(() -> assertThat(this.diskUsageOf(repoId)).isEqualTo(expected));
  }

  /** Every ERROR logged so far as {@code "<logger> <message>"}. */
  private List<String> errors() {
    return List.copyOf(this.logEvents.list).stream()
        .filter(event -> event.getLevel().isGreaterOrEqual(Level.ERROR))
        .map(event -> event.getLoggerName() + " " + event.getFormattedMessage())
        .toList();
  }

  private void awaitErrors(final List<String> expected) {
    await()
        .atMost(ASYNC_TIMEOUT)
        .untilAsserted(() -> assertThat(this.errors()).containsExactlyElementsOf(expected));
  }

  /** Keeps checking for {@link #QUIET_PERIOD} that no ERROR beyond {@code expected} is logged. */
  private void assertErrorsStayAt(final List<String> expected) {
    await()
        .during(QUIET_PERIOD)
        .atMost(QUIET_PERIOD.plus(ASYNC_TIMEOUT))
        .untilAsserted(() -> assertThat(this.errors()).containsExactlyElementsOf(expected));
  }

  private static String negativeUsageMessage(
      final UUID repoId, final long current, final long diff) {
    return "%s Repo %s disk usage would be negative: current %d, diff %d, clamping it to 0"
        .formatted(UsageUpdateService.class.getName(), repoId, current, diff);
  }

  @Test
  @DisplayName("clamps the usage at zero and logs the repo and the diff")
  void clampsAtZero() {
    final var repo = this.commitRepo();
    this.submitAndAwait(repo.getId(), 5, 5);

    this.submitAndAwait(repo.getId(), -8, 0);

    this.awaitErrors(List.of(negativeUsageMessage(repo.getId(), 5, -8)));
  }

  @Test
  @DisplayName("keeps applying later updates after a clamped one")
  void updatesAfterClampStillApply() {
    final var repo = this.commitRepo();
    this.submitAndAwait(repo.getId(), 5, 5);
    this.submitAndAwait(repo.getId(), -8, 0);

    this.submitAndAwait(repo.getId(), 3, 3);
    this.submitAndAwait(repo.getId(), -1, 2);

    this.awaitErrors(List.of(negativeUsageMessage(repo.getId(), 5, -8)));
  }

  @Test
  @DisplayName("clamps a negative diff on a repo without any usage")
  void clampsOnEmptyRepo() {
    final var repo = this.commitRepo();

    this.submit(repo.getId(), -4);

    this.awaitErrors(List.of(negativeUsageMessage(repo.getId(), 0, -4)));
    assertThat(this.diskUsageOf(repo.getId())).isZero();
  }

  @Test
  @DisplayName("logs no error when the usage drops to exactly zero")
  void reachingZeroIsNotAnError() {
    final var repo = this.commitRepo();
    this.submitAndAwait(repo.getId(), 5, 5);

    this.submitAndAwait(repo.getId(), -5, 0);

    this.assertErrorsStayAt(List.of());
  }

  @Test
  @DisplayName("clamps concurrent updates one by one, so the constraint is never violated")
  void concurrentUpdatesAreSerialized() {
    final var repo = this.commitRepo();
    this.submitAndAwait(repo.getId(), 10, 10);

    IntStream.range(0, 20).forEach(i -> this.submit(repo.getId(), -1));

    // 10 of the 20 decrements fit, the other 10 find the usage at zero and are clamped.
    final var expected =
        IntStream.range(0, 10).mapToObj(i -> negativeUsageMessage(repo.getId(), 0, -1)).toList();
    this.awaitErrors(expected);
    assertThat(this.diskUsageOf(repo.getId())).isZero();
    // A violated constraint would show as an ERROR from the async exception handler.
    this.assertErrorsStayAt(expected);
  }

  @Test
  @DisplayName("skips an update of a repo that was deleted, logging no error")
  void skipsDeletedRepo() {
    final var repo = this.commitRepo();
    this.repoRepository.deleteById(repo.getId());

    this.submit(repo.getId(), -4);

    this.assertErrorsStayAt(List.of());
  }
}
