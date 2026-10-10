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
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * V0044 (RPS-2121) on PostgreSQL: DROP INDEX CONCURRENTLY runs outside a transaction ({@code
 * executeInTransaction=false} in the {@code .sql.conf}), so a Flyway that ignored the config would
 * fail here. Legacy rows are seeded at V0043 and must survive; the covering index of every dropped
 * index must be valid and the planner must use it for the lookup a foreign key check issues. It
 * owns its container and touches no Spring context.
 */
@DisplayName("V0044 drops the redundant single-column indexes (PostgreSQL)")
class V0044DropRedundantIndexesIT {

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
  @DisplayName("drops the indexes, keeps the rows and the covering indexes serve the lookups")
  void dropsIndexesOnLegacyData() {
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
    final var scenario = new RedundantIndexesScenario(jdbc);

    flyway.target("43").load().migrate();
    for (final var dropped : RedundantIndexesScenario.DROPPED.keySet()) {
      assertThat(validity(jdbc, dropped)).as("%s before V0044", dropped).isTrue();
    }
    scenario.seed();

    flyway.target("44").load().migrate();

    for (final var covering : RedundantIndexesScenario.DROPPED.values()) {
      assertThat(validity(jdbc, covering)).as("%s is valid", covering).isTrue();
    }
    assertThat(plan(jdbc, "maven_artifact", "repo_id"))
        .contains("ux_maven_artifact__repo_id_group_artifact");
    assertThat(plan(jdbc, "maven_artifact_version", "artifact_id"))
        .contains("ux_maven_artifact_version__artifact_id_version_name");
    // any of the composite indexes that lead with repo_id serves the lookup
    assertThat(plan(jdbc, "vulnerability_scan", "repo_id"))
        .contains("Index Scan")
        .doesNotContain("Seq Scan");

    scenario.assertDropped(name -> validity(jdbc, name) != null);
  }

  /**
   * The plan of a foreign key lookup; sequential scans are off to get the choice for large tables.
   */
  private static String plan(final JdbcTemplate jdbc, final String table, final String column) {
    return jdbc.execute(
        (ConnectionCallback<String>)
            connection -> {
              try (final var statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = off");
                try (final var rows =
                    statement.executeQuery(
                        "EXPLAIN SELECT 1 FROM \"%s\" WHERE \"%s\" = gen_random_uuid()"
                            .formatted(table, column))) {
                  final var plan = new StringBuilder();
                  while (rows.next()) {
                    plan.append(rows.getString(1)).append('\n');
                  }

                  return plan.toString();
                }
              }
            });
  }

  private static Boolean validity(final JdbcTemplate jdbc, final String indexName) {
    final var rows =
        jdbc.queryForList(
            "SELECT i.indisvalid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid"
                + " WHERE c.relname = ?",
            Boolean.class,
            indexName);
    return rows.isEmpty() ? null : rows.get(0);
  }
}
