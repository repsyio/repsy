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
package io.repsy.os.server.protocols.helm.shared.oci.services;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.os.server.protocols.helm.shared.oci.repositories.HelmOciManifestMismatch;
import io.repsy.os.server.protocols.helm.shared.oci.services.HelmOciManifestNameRepairService.RepairReport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

@DisplayName("HelmOciManifestNameRepairRunner")
class HelmOciManifestNameRepairRunnerTest {

  private final HelmOciManifestNameRepairService service =
      mock(HelmOciManifestNameRepairService.class);
  private final ApplicationArguments arguments = mock(ApplicationArguments.class);
  private final HelmOciManifestNameRepairRunner runner =
      new HelmOciManifestNameRepairRunner(this.service);

  @Test
  @DisplayName("runs the repair on startup")
  void runsRepair() {
    when(this.service.repair()).thenReturn(new RepairReport(0, 0, List.of()));

    this.runner.run(this.arguments);

    verify(this.service).repair();
  }

  @Test
  @DisplayName("reports what was repaired and what was left")
  void reportsRepairedAndConflicts() {
    final var conflict =
        new HelmOciManifestMismatch(
            UUID.randomUUID(), UUID.randomUUID(), "repo", "alias", "1.0.0", "sha256:a", "chart");
    when(this.service.repair()).thenReturn(new RepairReport(2, 1, List.of(conflict)));

    assertThatCode(() -> this.runner.run(this.arguments)).doesNotThrowAnyException();

    verify(this.service).repair();
  }

  @Test
  @DisplayName("a failing repair does not stop the application from starting")
  void swallowsFailure() {
    when(this.service.repair()).thenThrow(new IllegalStateException("database is down"));

    assertThatCode(() -> this.runner.run(this.arguments)).doesNotThrowAnyException();
  }
}
