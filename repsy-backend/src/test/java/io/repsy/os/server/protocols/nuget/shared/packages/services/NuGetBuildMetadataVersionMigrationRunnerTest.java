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

import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
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
  @DisplayName("lists every conflict (repo, package, version) in a WARN that points to the README")
  void listsConflicts(final CapturedOutput output) {
    final var first =
        new NuGetBuildMetadataVersion(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "nuget-a",
            "some.package",
            "1.0.0+build");
    final var second =
        new NuGetBuildMetadataVersion(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "nuget-b",
            "other.package",
            "2.0.0+x");
    when(this.service.migrate()).thenReturn(new MigrationReport(0, 0, List.of(first, second)));

    this.runner.run(this.arguments);

    assertThat(output.getAll())
        .contains("WARN")
        .contains("(nuget-a, some.package, 1.0.0+build)")
        .contains("(nuget-b, other.package, 2.0.0+x)")
        .contains("NuGet versions with build metadata")
        .contains("Upgrading");
  }

  @Test
  @DisplayName("does not warn when nothing is left for an operator")
  void noConflictsNoWarning(final CapturedOutput output) {
    when(this.service.migrate()).thenReturn(new MigrationReport(3, 0, List.of()));

    this.runner.run(this.arguments);

    assertThat(output.getAll()).doesNotContain("left for an operator, their canonical");
  }

  @Test
  @DisplayName("a failing migration does not stop the application from starting")
  void swallowsFailure() {
    when(this.service.migrate()).thenThrow(new IllegalStateException("database is down"));

    assertThatCode(() -> this.runner.run(this.arguments)).doesNotThrowAnyException();
  }
}
