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

import db.migration.RedundantIndexesScenario;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * V0044 (RPS-2121) against an in-memory H2 database: legacy rows are seeded at V0043 and survive
 * the drops. {@code V0044DropRedundantIndexesIT} proves the PostgreSQL twin (DROP INDEX
 * CONCURRENTLY).
 */
@DisplayName("V0044 leaves the legacy rows intact (H2)")
class V0044DropRedundantIndexesTest {

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
    configuration.target(version).load().migrate();
  }

  @Test
  @DisplayName("keeps the rows and the cascade")
  void keepsLegacyRows() {
    final var jdbc = new JdbcTemplate(this.dataSource);
    final var scenario = new RedundantIndexesScenario(jdbc);

    this.migrateTo("43");
    scenario.seed();
    this.migrateTo("44");

    scenario.assertRowsIntact();
  }
}
