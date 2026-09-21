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
package io.repsy.os.server.protocols.nuget.shared.packages.entities;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps the {@code @Column} annotations of the NuGet entities equal to the PostgreSQL schema Flyway
 * creates. Hibernate does not validate them, so a stale length would only mislead (RPS-1069). It
 * only reads {@code information_schema}, so it commits no rows.
 */
@DisplayName("NuGet entity @Column annotations against the PostgreSQL schema")
class NuGetSchemaAnnotationIT extends AbstractIntegrationTest {

  @Test
  @DisplayName("NuGetPackage matches nuget_package")
  void nuGetPackageMatchesSchema() {
    assertThat(NuGetColumnSchemaChecks.postgresProblems(this.jdbcTemplate, NuGetPackage.class))
        .as("NuGetPackage annotations that differ from the PostgreSQL schema")
        .isEmpty();
  }

  @Test
  @DisplayName("NuGetPackageVersion matches nuget_package_version")
  void nuGetPackageVersionMatchesSchema() {
    assertThat(
            NuGetColumnSchemaChecks.postgresProblems(this.jdbcTemplate, NuGetPackageVersion.class))
        .as("NuGetPackageVersion annotations that differ from the PostgreSQL schema")
        .isEmpty();
  }
}
