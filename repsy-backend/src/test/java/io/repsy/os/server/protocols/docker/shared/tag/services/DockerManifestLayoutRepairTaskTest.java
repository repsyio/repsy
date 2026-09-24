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
package io.repsy.os.server.protocols.docker.shared.tag.services;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.os.server.protocols.docker.shared.tag.services.DockerManifestLayoutRepairService.RepairReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DockerManifestLayoutRepairTask")
class DockerManifestLayoutRepairTaskTest {

  private final DockerManifestLayoutRepairService service =
      mock(DockerManifestLayoutRepairService.class);
  private final DockerManifestLayoutRepairTask task =
      new DockerManifestLayoutRepairTask(this.service);

  @Test
  @DisplayName("runs the repair")
  void runsTheRepair() {
    when(this.service.repair()).thenReturn(new RepairReport(0, 0, 0));

    this.task.repair();

    verify(this.service).repair();
  }

  @Test
  @DisplayName("reports what was repaired and what was left")
  void reportsTheOutcome() {
    when(this.service.repair()).thenReturn(new RepairReport(3, 1, 2));

    assertThatCode(this.task::repair).doesNotThrowAnyException();

    verify(this.service).repair();
  }

  @Test
  @DisplayName("a failing repair does not stop the scheduler thread")
  void swallowsAFailure() {
    when(this.service.repair()).thenThrow(new IllegalStateException("database is down"));

    assertThatCode(this.task::repair).doesNotThrowAnyException();
  }
}
