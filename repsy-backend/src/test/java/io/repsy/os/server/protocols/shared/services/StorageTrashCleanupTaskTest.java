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

import io.repsy.libs.storage.core.services.StorageStrategy;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;

@DisplayName("StorageTrashCleanupTask")
class StorageTrashCleanupTaskTest {

  private static Map<String, StorageStrategy> strategies(final StorageStrategy... strategies) {
    final var byType = new LinkedHashMap<String, StorageStrategy>();
    for (var i = 0; i < strategies.length; i++) {
      byType.put("TYPE" + i, strategies[i]);
    }
    return byType;
  }

  @Test
  @DisplayName("cleanup() empties the trash of every storage strategy exactly once")
  void clearsTheTrashOfEveryStrategyOnce() {
    final var first = mock(StorageStrategy.class);
    final var second = mock(StorageStrategy.class);
    final var third = mock(StorageStrategy.class);

    new StorageTrashCleanupTask(strategies(first, second, third)).cleanup();

    verify(first).clearTrash();
    verify(second).clearTrash();
    verify(third).clearTrash();
    verifyNoMoreInteractions(first, second, third);
  }

  @Test
  @DisplayName("a strategy that fails does not stop the others and does not propagate")
  void aFailingStrategyDoesNotStopTheOthers() {
    final var before = mock(StorageStrategy.class);
    final var failing = mock(StorageStrategy.class);
    final var after = mock(StorageStrategy.class);
    doThrow(new IllegalStateException("trash is unreadable")).when(failing).clearTrash();

    assertThatCode(() -> new StorageTrashCleanupTask(strategies(before, failing, after)).cleanup())
        .doesNotThrowAnyException();

    verify(before).clearTrash();
    verify(failing).clearTrash();
    verify(after).clearTrash();
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
