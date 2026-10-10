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
 * V0042 and V0043 (RPS-2120) on PostgreSQL. V0043 commits after each batch of its DO block, so it
 * runs outside a transaction ({@code executeInTransaction=false}); a Flyway that ignored the config
 * would fail here. The seeded images span several 5000-row batches. It owns its container and
 * touches no Spring context.
 */
@DisplayName("V0042-V0043 store the tag statistics of every Docker image (PostgreSQL)")
class V0043DockerImageTagStatsBackfillIT {

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
  @DisplayName("backfills seeded images in batches and adds the columns with their defaults")
  void backfillsInBatches() {
    final var dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    final var flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/postgresql")
            // The application sets the same (spring.flyway.postgresql.transactional-lock=false).
            .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
            .schemas("public")
            .defaultSchema("public");
    final var jdbc = new JdbcTemplate(dataSource);
    // 12000 bulk images: the backfill runs three batches of 5000 rows.
    final var scenario = new DockerImageTagStatsScenario(jdbc, 12000);

    flyway.target("41").load().migrate();
    scenario.seed();

    flyway.target("43").load().migrate();

    scenario.assertBackfilled();
    assertThat(
            jdbc.queryForList(
                "SELECT is_nullable || ':' || coalesce(column_default, '') FROM"
                    + " information_schema.columns WHERE table_name = 'docker_image'"
                    + " AND column_name = 'tag_count'",
                String.class))
        .containsExactly("NO:0");
  }
}
