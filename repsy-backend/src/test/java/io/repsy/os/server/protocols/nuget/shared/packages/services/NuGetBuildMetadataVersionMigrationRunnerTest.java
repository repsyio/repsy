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
package io.repsy.os.server.protocols.nuget.shared.packages.services;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetBuildMetadataVersion;
import io.repsy.os.server.protocols.nuget.shared.packages.services.NuGetBuildMetadataVersionMigrationService.MigrationReport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

@DisplayName("NuGetBuildMetadataVersionMigrationRunner (RPS-1059)")
class NuGetBuildMetadataVersionMigrationRunnerTest {

  private final NuGetBuildMetadataVersionMigrationService service =
      mock(NuGetBuildMetadataVersionMigrationService.class);
  private final ApplicationArguments arguments = mock(ApplicationArguments.class);
  private final NuGetBuildMetadataVersionMigrationRunner runner =
      new NuGetBuildMetadataVersionMigrationRunner(this.service);

  @Test
  @DisplayName("runs the migration on startup")
  void runsMigration() {
    when(this.service.migrate()).thenReturn(new MigrationReport(0, 0, List.of()));

    this.runner.run(this.arguments);

    verify(this.service).migrate();
  }

  @Test
  @DisplayName("reports what was migrated, what failed and what was left")
  void reportsMigratedFailedAndConflicts() {
    final var conflict =
        new NuGetBuildMetadataVersion(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "repo",
            "some.package",
            "1.0.0+build");
    when(this.service.migrate()).thenReturn(new MigrationReport(2, 1, List.of(conflict)));

    assertThatCode(() -> this.runner.run(this.arguments)).doesNotThrowAnyException();

    verify(this.service).migrate();
  }

  @Test
  @DisplayName("a failing migration does not stop the application from starting")
  void swallowsFailure() {
    when(this.service.migrate()).thenThrow(new IllegalStateException("database is down"));

    assertThatCode(() -> this.runner.run(this.arguments)).doesNotThrowAnyException();
  }
}
