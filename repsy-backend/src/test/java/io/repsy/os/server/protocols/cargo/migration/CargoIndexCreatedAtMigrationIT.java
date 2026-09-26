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
package io.repsy.os.server.protocols.cargo.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * V0030 (RPS-1605, {@code cargo_crate_index.created_at}) on a populated PostgreSQL database: the
 * legacy data of {@link CargoIndexCreatedAtMigrationScenario} is written at V0029, migrated to the
 * latest version and checked. It owns its container and touches no Spring context, so it neither
 * shares nor disturbs the database the other integration tests use; the same scenario runs on H2 in
 * {@code V0030CargoCrateIndexCreatedAtTest}.
 */
@DisplayName("V0030 cargo_crate_index.created_at is backfilled from the publish time (PostgreSQL)")
class CargoIndexCreatedAtMigrationIT {

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
            .schemas("public")
            .defaultSchema("public");

    (version == null ? configuration : configuration.target(version)).load().migrate();
  }

  @Test
  @DisplayName("backfills a populated database from the meta rows")
  void migratesLegacyData() {
    final var scenario = new CargoIndexCreatedAtMigrationScenario(new JdbcTemplate(dataSource));
    migrateTo("29");
    scenario.seed();

    migrateTo(null);

    scenario.verify();
  }
}
