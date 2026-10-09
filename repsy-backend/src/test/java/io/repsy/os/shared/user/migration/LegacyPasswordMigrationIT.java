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
package io.repsy.os.shared.user.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * V0017 and V0031 (RPS-1615, an upgrade from v26.08.4 keeps every password) on a populated
 * PostgreSQL database: the accounts of {@link LegacyPasswordMigrationScenario} are written at
 * V0016, migrated to the latest version and checked. It owns its container and touches no Spring
 * context, so it neither shares nor disturbs the database the other integration tests use; the same
 * scenario runs on H2 in {@code V0031RestoreUserSaltTest}.
 */
@DisplayName("Legacy password hashes survive the migrations (PostgreSQL)")
class LegacyPasswordMigrationIT {

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

  /** Each test starts from an empty schema. */
  private static void clean() {
    Flyway.configure().dataSource(dataSource).schemas("public").cleanDisabled(false).load().clean();
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
  @DisplayName("keeps the hash, salt and token version of every account")
  void keepsLegacyHashes() {
    clean();
    final var scenario = new LegacyPasswordMigrationScenario(new JdbcTemplate(dataSource));
    migrateTo("16");
    scenario.seed();

    migrateTo(null);

    scenario.verifyKept();
  }

  @Test
  @DisplayName("puts the salt column back, nullable, on a database that ran the old V0017")
  void restoresTheColumnAfterTheOldV0017() {
    clean();
    final var scenario = new LegacyPasswordMigrationScenario(new JdbcTemplate(dataSource));
    migrateTo("16");
    scenario.seed();
    scenario.applyOldV0017();

    migrateTo(null);

    scenario.verifyColumnRestored();
  }
}
