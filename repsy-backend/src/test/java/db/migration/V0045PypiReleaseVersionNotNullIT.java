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
package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * V0045 (RPS-2198) on PostgreSQL: pypi_release.version is nullable at V0044 and NOT NULL after
 * V0045, and an insert of a null version is then refused. It owns its container and touches no
 * Spring context.
 */
@DisplayName("V0045 makes pypi_release.version NOT NULL (PostgreSQL)")
class V0045PypiReleaseVersionNotNullIT {

  private static PostgreSQLContainer<?> postgres;

  @BeforeAll
  static void startDatabase() {
    postgres =
        new PostgreSQLContainer<>("postgres:18")
            .withDatabaseName("repsy")
            .withUsername("repsy")
            .withPassword("repsy123");
    postgres.start();
  }

  @AfterAll
  static void stopDatabase() {
    postgres.stop();
  }

  @Test
  @DisplayName("flips the column from nullable to NOT NULL")
  void flipsToNotNull() {
    final var dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    final var flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/postgresql")
            .schemas("public")
            .defaultSchema("public")
            // V0044 and earlier build indexes CONCURRENTLY, which waits forever behind Flyway's own
            // transactional advisory lock (RPS-2112).
            .configuration(Map.of("flyway.postgresql.transactional.lock", "false"));
    final var jdbc = new JdbcTemplate(dataSource);
    final var query =
        "SELECT is_nullable FROM information_schema.columns"
            + " WHERE table_name = 'pypi_release' AND column_name = 'version'";

    flyway.target("44").load().migrate();
    assertThat(jdbc.queryForObject(query, String.class)).isEqualTo("YES");

    flyway.target("45").load().migrate();
    assertThat(jdbc.queryForObject(query, String.class)).isEqualTo("NO");
  }
}
