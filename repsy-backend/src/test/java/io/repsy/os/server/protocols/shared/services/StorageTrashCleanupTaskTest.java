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
package io.repsy.os.server.protocols.shared.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.repsy.libs.storage.core.dtos.TrashCleanupResult;
import io.repsy.libs.storage.core.services.StorageStrategy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;

@DisplayName("StorageTrashCleanupTask")
class StorageTrashCleanupTaskTest {

  private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
  private Logger taskLogger;
  private Level originalLevel;

  @BeforeEach
  void attachLogAppender() {
    this.taskLogger = (Logger) LoggerFactory.getLogger(StorageTrashCleanupTask.class);
    // Another test in the same JVM may have raised the level (a Spring context applies the logging
    // config), which would drop the INFO events this test reads.
    this.originalLevel = this.taskLogger.getLevel();
    this.taskLogger.setLevel(Level.INFO);
    this.logAppender.start();
    this.taskLogger.addAppender(this.logAppender);
  }

  @AfterEach
  void detachLogAppender() {
    this.taskLogger.detachAppender(this.logAppender);
    this.taskLogger.setLevel(this.originalLevel);
  }

  private static Map<String, StorageStrategy> strategies(final StorageStrategy... strategies) {
    final var byType = new LinkedHashMap<String, StorageStrategy>();
    for (var i = 0; i < strategies.length; i++) {
      byType.put("TYPE" + i, strategies[i]);
    }
    return byType;
  }

  private static void stubResult(final StorageStrategy strategy, final TrashCleanupResult result) {
    when(strategy.clearTrash()).thenReturn(CompletableFuture.completedFuture(result));
  }

  @Test
  @DisplayName("cleanup() empties the trash of every storage strategy exactly once")
  void clearsTheTrashOfEveryStrategyOnce() {
    final var first = mock(StorageStrategy.class);
    final var second = mock(StorageStrategy.class);
    final var third = mock(StorageStrategy.class);
    stubResult(first, TrashCleanupResult.EMPTY);
    stubResult(second, TrashCleanupResult.EMPTY);
    stubResult(third, TrashCleanupResult.EMPTY);

    new StorageTrashCleanupTask(strategies(first, second, third)).cleanup();

    verify(first).clearTrash();
    verify(second).clearTrash();
    verify(third).clearTrash();
    verifyNoMoreInteractions(first, second, third);
  }

  @Test
  @DisplayName("logs one info line per strategy summarizing what the pass removed")
  void logsAnInfoLinePerStrategyResult() {
    final var strategy = mock(StorageStrategy.class);
    stubResult(strategy, new TrashCleanupResult(2, 5, 1234L));

    new StorageTrashCleanupTask(strategies(strategy)).cleanup();

    assertThat(this.logAppender.list)
        .filteredOn(event -> event.getLevel() == Level.INFO)
        .anySatisfy(
            event -> {
              final var message = event.getFormattedMessage();
              assertThat(message).contains("TYPE0").contains("2").contains("5").contains("1234");
            });
  }

  @Test
  @DisplayName("a strategy whose submission fails synchronously does not stop the others")
  void aStrategyThatFailsToSubmitDoesNotStopTheOthers() {
    final var before = mock(StorageStrategy.class);
    final var failing = mock(StorageStrategy.class);
    final var after = mock(StorageStrategy.class);
    stubResult(before, TrashCleanupResult.EMPTY);
    stubResult(after, TrashCleanupResult.EMPTY);
    doThrow(new IllegalStateException("executor rejected the task")).when(failing).clearTrash();

    assertThatCode(() -> new StorageTrashCleanupTask(strategies(before, failing, after)).cleanup())
        .doesNotThrowAnyException();

    verify(before).clearTrash();
    verify(failing).clearTrash();
    verify(after).clearTrash();
  }

  @Test
  @DisplayName(
      "a strategy whose pass fails asynchronously logs a warning and does not stop the others")
  void aStrategyThatFailsAsynchronouslyDoesNotStopTheOthers() {
    final var before = mock(StorageStrategy.class);
    final var failing = mock(StorageStrategy.class);
    final var after = mock(StorageStrategy.class);
    stubResult(before, TrashCleanupResult.EMPTY);
    stubResult(after, TrashCleanupResult.EMPTY);
    when(failing.clearTrash())
        .thenReturn(
            CompletableFuture.failedFuture(new IllegalStateException("trash is unreadable")));

    assertThatCode(() -> new StorageTrashCleanupTask(strategies(before, failing, after)).cleanup())
        .doesNotThrowAnyException();

    verify(before).clearTrash();
    verify(failing).clearTrash();
    verify(after).clearTrash();
    assertThat(this.logAppender.list)
        .filteredOn(event -> event.getLevel() == Level.WARN)
        .anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("TYPE1"));
  }

  @Test
  @DisplayName("cleanup() with no strategies does nothing")
  void noStrategiesIsFine() {
    assertThatCode(() -> new StorageTrashCleanupTask(Map.of()).cleanup())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName(
      "cleanup() is scheduled by the configured interval and delay, without its own @Async")
  void cleanupIsScheduledWithTheConfiguredDurations() throws NoSuchMethodException {
    final var method = StorageTrashCleanupTask.class.getMethod("cleanup");

    final var scheduled = AnnotationUtils.findAnnotation(method, Scheduled.class);

    assertThat(scheduled).isNotNull();
    assertThat(scheduled.fixedDelayString()).contains("trash-cleanup.interval").contains("PT24H");
    assertThat(scheduled.initialDelayString())
        .contains("trash-cleanup.initial-delay")
        .contains("PT15M");
    // clearTrash is @Async itself: a second hop would only queue the submission of nine tasks
    assertThat(AnnotationUtils.findAnnotation(method, Async.class)).isNull();
  }
}
