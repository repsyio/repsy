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
package db.migration.h2;

import io.repsy.os.server.protocols.cargo.migration.CargoIndexCreatedAtMigrationScenario;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * V0030 (RPS-1605, {@code cargo_crate_index.created_at}) against an in-memory H2 database, so it
 * needs no Docker: legacy rows written at V0029 are backfilled from the publish time in {@code
 * cargo_crate_meta}. {@code CargoIndexCreatedAtMigrationIT} runs the same scenario on PostgreSQL.
 */
@DisplayName("V0030 cargo_crate_index.created_at is backfilled from the publish time (H2)")
class V0030CargoCrateIndexCreatedAtTest {

  private SingleConnectionDataSource dataSource;

  @BeforeEach
  void setUp() {
    this.dataSource =
        new SingleConnectionDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "sa",
            "",
            true);
  }

  @AfterEach
  void tearDown() {
    this.dataSource.destroy();
  }

  private void migrateTo(final String version) {
    final var configuration =
        Flyway.configure()
            .dataSource(this.dataSource)
            .locations("classpath:db/migration/h2")
            .schemas("public")
            .defaultSchema("public");

    (version == null ? configuration : configuration.target(version)).load().migrate();
  }

  @Test
  @DisplayName("backfills a populated database from the meta rows")
  void migratesLegacyData() {
    final var jdbc = new JdbcTemplate(this.dataSource);
    final var scenario = new CargoIndexCreatedAtMigrationScenario(jdbc);
    this.migrateTo("29");
    // Flyway creates the lower-case "public" schema, which H2 does not search by default.
    jdbc.execute("SET SCHEMA \"public\"");
    scenario.seed();

    this.migrateTo(null);

    jdbc.execute("SET SCHEMA \"public\"");
    scenario.verify();
  }
}
