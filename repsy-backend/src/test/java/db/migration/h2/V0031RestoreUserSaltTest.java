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

import io.repsy.os.shared.user.migration.LegacyPasswordMigrationScenario;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * V0017 and V0031 (RPS-1615, an upgrade from v26.08.4 keeps every password) against an in-memory H2
 * database, so it needs no Docker. {@code LegacyPasswordMigrationIT} runs the same scenario on
 * PostgreSQL.
 */
@DisplayName("Legacy password hashes survive the migrations (H2)")
class V0031RestoreUserSaltTest {

  private SingleConnectionDataSource dataSource;

  /**
   * One connection for the whole test. H2 evaluates the role check constraint of {@code users} with
   * the session that created it, so a session that Flyway closed would fail every later insert.
   */
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

  private JdbcTemplate jdbc() {
    final var jdbc = new JdbcTemplate(this.dataSource);
    // Flyway creates the lower-case "public" schema, which H2 does not search by default.
    jdbc.execute("SET SCHEMA \"public\"");

    return jdbc;
  }

  @Test
  @DisplayName("keeps the hash, salt and token version of every account")
  void keepsLegacyHashes() {
    this.migrateTo("16");
    final var scenario = new LegacyPasswordMigrationScenario(this.jdbc());
    scenario.seed();

    this.migrateTo(null);

    this.jdbc();
    scenario.verifyKept();
  }

  @Test
  @DisplayName("puts the salt column back, nullable, on a database that ran the old V0017")
  void restoresTheColumnAfterTheOldV0017() {
    this.migrateTo("16");
    final var scenario = new LegacyPasswordMigrationScenario(this.jdbc());
    scenario.seed();
    scenario.applyOldV0017();

    this.migrateTo(null);

    this.jdbc();
    scenario.verifyColumnRestored();
  }
}
