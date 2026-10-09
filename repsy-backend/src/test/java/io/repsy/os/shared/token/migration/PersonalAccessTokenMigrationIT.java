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
package io.repsy.os.shared.token.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * V0034 ({@code personal_access_token}, RPS-1901) on PostgreSQL: a database at V0033 with a user is
 * migrated to the latest version and {@link PersonalAccessTokenMigrationScenario} checks the table.
 * It owns its container and touches no Spring context, so it neither shares nor disturbs the
 * database the other integration tests use; the same scenario runs on H2 in {@code
 * V0034AddPersonalAccessTokensTest}.
 */
@DisplayName("V0034 personal_access_token (PostgreSQL)")
class PersonalAccessTokenMigrationIT {

  private static PostgreSQLContainer<?> postgres;
  private static DriverManagerDataSource dataSource;

  @BeforeAll
  static void startDatabase() {
    postgres =
        new PostgreSQLContainer<>("postgres:18")
            .withDatabaseName("repsy")
            .withUsername("repsy")
            .withPassword("repsy123");
    postgres.start();
    dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  @AfterAll
  static void stopDatabase() {
    postgres.stop();
  }

  private static void migrateTo(final String version) {
    final var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/postgresql")
            // Same as the application: the default transactional lock would block V0035
            // (CONCURRENTLY).
            .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
            .schemas("public")
            .defaultSchema("public");

    (version == null ? configuration : configuration.target(version)).load().migrate();
  }

  @Test
  @DisplayName("creates the table on a database that is in use, with the schema the entity expects")
  void migratesADatabaseInUse() {
    final var scenario = new PersonalAccessTokenMigrationScenario(new JdbcTemplate(dataSource));
    migrateTo("33");
    scenario.seed();

    migrateTo(null);

    scenario.verify();
  }
}
