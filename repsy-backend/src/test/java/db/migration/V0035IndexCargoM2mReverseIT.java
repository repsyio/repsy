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
 * V0035 (RPS-2112) on PostgreSQL: the migration runs outside a transaction (CREATE INDEX
 * CONCURRENTLY, {@code executeInTransaction=false} in the {@code .sql.conf}), so a Flyway that
 * ignored the config would fail here. Every index must be valid, and the planner must use it for
 * the lookup the ON DELETE CASCADE check issues. It owns its container and touches no Spring
 * context.
 */
@DisplayName(
    "V0035 indexes the cargo join table reverse sides and key_store keyserver (PostgreSQL)")
class V0035IndexCargoM2mReverseIT {

  private static final Map<String, String> INDEXES =
      Map.of(
          "idx_cargo_crate_author__author_id", "cargo_crate_author|author_id",
          "idx_cargo_crate_keyword__keyword_id", "cargo_crate_keyword|keyword_id",
          "idx_cargo_crate_category__category_id", "cargo_crate_category|category_id",
          "idx_key_store__allowed_keyserver_id", "key_store|allowed_keyserver_id");

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
  @DisplayName("creates valid indexes that the foreign key lookups use")
  void createsValidIndexes() {
    final var dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    final var flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/postgresql")
            // The application sets the same (spring.flyway.postgresql.transactional-lock=false):
            // with the default transactional advisory lock Flyway's own open transaction makes
            // CREATE INDEX CONCURRENTLY wait for it forever.
            .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
            .schemas("public")
            .defaultSchema("public");
    final var jdbc = new JdbcTemplate(dataSource);

    flyway.target("34").load().migrate();
    for (final var name : INDEXES.keySet()) {
      assertThat(validity(jdbc, name)).as("%s before V0035", name).isNull();
    }

    flyway.target("35").load().migrate();

    for (final var entry : INDEXES.entrySet()) {
      assertThat(validity(jdbc, entry.getKey())).as(entry.getKey()).isTrue();
      final var parts = entry.getValue().split("\\|");
      assertThat(plan(jdbc, parts[0], parts[1]))
          .as("plan of %s", entry.getKey())
          .contains(entry.getKey());
    }
  }

  /**
   * The plan of the lookup the ON DELETE CASCADE check issues. The tables are empty, so sequential
   * scans are switched off on the one connection to get the choice the planner makes on a table
   * with rows.
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
