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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
@DisplayName("UsageUpdateService")
class UsageUpdateServiceTest {

  private static final UUID REPO_ID = UUID.randomUUID();

  @Mock RepoTxService repoTxService;

  @InjectMocks UsageUpdateService usageUpdateService;

  private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
  private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(UsageUpdateService.class);

  @BeforeEach
  void captureLogs() {
    this.logEvents.start();
    this.serviceLogger.addAppender(this.logEvents);
  }

  @AfterEach
  void releaseLogs() {
    this.serviceLogger.detachAppender(this.logEvents);
    this.logEvents.stop();
  }

  private void updateUsage(final long diskUsageDiff) {
    this.usageUpdateService.updateUsage(
        new UsageChangedInfo(REPO_ID, BaseUsages.ofDisk(diskUsageDiff)));
  }

  private long errorCount() {
    return this.logEvents.list.stream()
        .filter(event -> event.getLevel().isGreaterOrEqual(Level.ERROR))
        .count();
  }

  @Test
  @DisplayName("skips the update without an error when the repo no longer exists")
  void skipsMissingRepo() {
    when(this.repoTxService.updateDiskUsage(REPO_ID, 10)).thenReturn(false);

    this.updateUsage(10);

    verify(this.repoTxService, never()).findDiskUsage(REPO_ID);
    assertThat(this.errorCount()).isZero();
  }

  @Test
  @DisplayName("logs no error when the usage after the update is not negative")
  void nonNegativeUsage() {
    when(this.repoTxService.updateDiskUsage(REPO_ID, -5)).thenReturn(true);
    when(this.repoTxService.findDiskUsage(REPO_ID)).thenReturn(Optional.of(0L));

    this.updateUsage(-5);

    assertThat(this.errorCount()).isZero();
  }

  @Test
  @DisplayName("logs an error when the usage read after the update is negative")
  void negativeUsage() {
    when(this.repoTxService.updateDiskUsage(REPO_ID, -5)).thenReturn(true);
    when(this.repoTxService.findDiskUsage(REPO_ID)).thenReturn(Optional.of(-3L));

    this.updateUsage(-5);

    assertThat(this.logEvents.list)
        .filteredOn(event -> event.getLevel() == Level.ERROR)
        .extracting(ILoggingEvent::getFormattedMessage)
        .containsExactly("Repo %s disk usage is negative: -3".formatted(REPO_ID));
  }

  @Test
  @DisplayName("logs no error when the repo is deleted between the update and the read")
  void repoDeletedAfterUpdate() {
    when(this.repoTxService.updateDiskUsage(REPO_ID, 10)).thenReturn(true);
    when(this.repoTxService.findDiskUsage(REPO_ID)).thenReturn(Optional.empty());

    this.updateUsage(10);

    assertThat(this.errorCount()).isZero();
  }
}
