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
package io.repsy.os.server.protocols.docker.shared.image.tasks;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.os.server.protocols.docker.shared.image.services.DockerImageStatsBackfillService;
import io.repsy.os.server.protocols.docker.shared.image.services.DockerImageStatsBackfillService.BackfillReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DockerImageStatsBackfillTask")
class DockerImageStatsBackfillTaskTest {

  private final DockerImageStatsBackfillService service =
      mock(DockerImageStatsBackfillService.class);
  private final DockerImageStatsBackfillTask task = new DockerImageStatsBackfillTask(this.service);

  @Test
  @DisplayName("runs the backfill")
  void runsTheBackfill() {
    when(this.service.backfill()).thenReturn(new BackfillReport(0, 0));

    this.task.backfill();

    verify(this.service).backfill();
  }

  @Test
  @DisplayName("reports what was refreshed and what failed")
  void reportsTheOutcome() {
    when(this.service.backfill()).thenReturn(new BackfillReport(3, 1));

    assertThatCode(this.task::backfill).doesNotThrowAnyException();

    verify(this.service).backfill();
  }

  @Test
  @DisplayName("a failing backfill does not stop the scheduler thread")
  void swallowsAFailure() {
    when(this.service.backfill()).thenThrow(new IllegalStateException("database is down"));

    assertThatCode(this.task::backfill).doesNotThrowAnyException();
  }
}
