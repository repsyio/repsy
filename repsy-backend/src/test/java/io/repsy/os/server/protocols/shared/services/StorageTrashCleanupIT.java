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
import static org.awaitility.Awaitility.await;

import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.util.FileSystemUtils;

/**
 * The storage trash is emptied on a schedule (RPS-1096).
 *
 * <p>Every protocol has a strategy and a trash directory of its own, so the job has to reach all of
 * them, and it has to honour the retention of the running application (seven days by default), not
 * the zero retention the strategies used to be built with. The test only touches the temporary
 * storage root of this test class: it asserts that every trash path lives under it before it lets
 * anything be deleted, and it deletes nothing but the directories it created itself.
 *
 * <p>No database row is written, so the committed-rows guard has nothing to check.
 */
@DisplayName("Storage trash cleanup")
class StorageTrashCleanupIT extends AbstractIntegrationTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(20);

  /** Well outside the seven-day retention, and never a name another test creates. */
  private static final String EXPIRED_DAY = "2001-02-03";

  @Autowired private StorageTrashCleanupTask task;

  @Autowired
  @Qualifier("storageStrategiesByRepoType")
  private Map<String, StorageStrategy> strategies;

  @Autowired private ScheduledAnnotationBeanPostProcessor scheduledTasks;
  @Autowired private Environment environment;

  private List<Path> trashPaths;

  private static String today() {
    return LocalDate.now(ZoneId.systemDefault()).toString();
  }

  private static String daysAgo(final int days) {
    return LocalDate.now(ZoneId.systemDefault()).minusDays(days).toString();
  }

  @BeforeEach
  void requireTrashInsideTheTemporaryStorageRoot() {
    this.trashPaths =
        Arrays.stream(RepoType.values())
            .map(
                type ->
                    this.environment.getRequiredProperty(
                        "os.app.storage.file-system." + protocolDir(type) + ".trash-path"))
            .map(Path::of)
            .toList();

    assertThat(this.trashPaths).hasSize(9).doesNotHaveDuplicates();
    assertThat(this.trashPaths).allMatch(path -> path.startsWith(STORAGE_ROOT));
  }

  @AfterEach
  void removeWhatTheTestCreated() {
    this.trashPaths.forEach(
        path -> {
          for (final var day : List.of(EXPIRED_DAY, daysAgo(3), daysAgo(30), "1999-12-31")) {
            deleteInsideStorageRoot(path.resolve(day));
          }
        });
  }

  private static void deleteInsideStorageRoot(final Path path) {
    if (!path.startsWith(STORAGE_ROOT)) {
      throw new IllegalStateException("refusing to delete outside the test storage: " + path);
    }
    try {
      FileSystemUtils.deleteRecursively(path);
    } catch (final IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Path makeTrash(final Path trashPath, final String day) throws IOException {
    final var dir = Files.createDirectories(trashPath.resolve(day).resolve("deleted-item"));
    Files.writeString(dir.resolve("file.txt"), "deleted");
    return trashPath.resolve(day);
  }

  @Test
  @DisplayName("the cleanup is registered with the scheduler")
  void cleanupIsScheduled() {
    final var registered =
        this.scheduledTasks.getScheduledTasks().stream()
            .filter(scheduled -> scheduled.getTask().toString().contains("StorageTrashCleanupTask"))
            .toList();

    assertThat(registered).hasSize(1);
  }

  @Test
  @DisplayName("every storage strategy is a proxy, so clearTrash is handed to the maintenance pool")
  void everyStrategyIsProxied() {
    assertThat(this.strategies).hasSize(9);
    assertThat(this.strategies.values())
        .allSatisfy(s -> assertThat(AopUtils.isAopProxy(s)).isTrue());
    assertThat(this.strategies.values().stream().distinct().count()).isEqualTo(9);
  }

  @Test
  @DisplayName("expired trash of all nine protocols is deleted, trash inside the retention is kept")
  void expiredTrashOfEveryProtocolIsDeleted() throws Exception {
    final var expired = new ArrayList<Path>();
    final var withinRetention = new ArrayList<Path>();
    final var today = new ArrayList<Path>();
    for (final var trashPath : this.trashPaths) {
      expired.add(makeTrash(trashPath, EXPIRED_DAY));
      withinRetention.add(makeTrash(trashPath, daysAgo(3)));
      today.add(makeTrash(trashPath, today()));
    }

    this.task.cleanup();

    await().atMost(TIMEOUT).untilAsserted(() -> assertThat(expired).noneMatch(Files::exists));
    assertThat(withinRetention).allMatch(Files::exists);
    assertThat(today).allMatch(Files::exists);
  }
}
