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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * V0040 and V0041 (RPS-2117) on PostgreSQL: the extension, then trigram indexes built outside a
 * transaction ({@code executeInTransaction=false} in the {@code .sql.conf}). It owns its containers
 * and touches no Spring context.
 */
@DisplayName("V0040 and V0041 install pg_trgm and its search indexes (PostgreSQL)")
class V0041TrigramSearchIndexesIT {

  private static final List<String> INDEXES =
      List.of(
          "idx_maven_artifact__group_artifact_trgm",
          "idx_npm_package__scope_name_trgm",
          "idx_docker_image__name_trgm",
          "idx_pypi_package__name_trgm",
          "idx_helm_chart__name_trgm",
          "idx_ruby_gem__name_trgm",
          "idx_go_module__module_path_trgm",
          "idx_nuget_package__package_id_trgm",
          "idx_users__username_trgm");

  private static Flyway flyway(final DriverManagerDataSource dataSource, final String target) {
    return Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/postgresql")
        // The application sets the same (spring.flyway.postgresql.transactional-lock=false):
        // with the default transactional advisory lock Flyway's own open transaction makes
        // CREATE INDEX CONCURRENTLY wait for it forever.
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
        .schemas("public")
        .defaultSchema("public")
        .target(target)
        .load();
  }

  @Test
  @DisplayName("creates the extension and valid indexes that serve a contains search")
  void createsExtensionAndValidIndexes() {
    try (final var postgres =
        new PostgreSQLContainer<>("postgres:18")
            .withDatabaseName("repsy")
            .withUsername("repsy")
            .withPassword("repsy123")) {
      postgres.start();
      final var dataSource =
          new DriverManagerDataSource(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
      final var jdbc = new JdbcTemplate(dataSource);

      flyway(dataSource, "39").migrate();
      assertThat(extensions(jdbc)).isEmpty();

      flyway(dataSource, "41").migrate();

      assertThat(extensions(jdbc)).containsExactly("pg_trgm");
      for (final var index : INDEXES) {
        final var valid =
            jdbc.queryForList(
                "SELECT i.indisvalid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid"
                    + " WHERE c.relname = ?",
                Boolean.class,
                index);
        assertThat(valid).as(index).containsExactly(true);
      }
      final var definition =
          jdbc.queryForObject(
              "SELECT indexdef FROM pg_indexes WHERE indexname = ?",
              String.class,
              "idx_maven_artifact__group_artifact_trgm");
      assertThat(definition).contains("USING gin").contains("gin_trgm_ops");
    }
  }

  @Test
  @DisplayName("fails with a message that names the missing privilege")
  void failsClearlyWithoutPrivilege() {
    try (final var postgres =
        new PostgreSQLContainer<>("postgres:18")
            .withDatabaseName("repsy")
            .withUsername("repsy")
            .withPassword("repsy123")) {
      postgres.start();
      final var owner =
          new DriverManagerDataSource(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
      final var jdbc = new JdbcTemplate(owner);
      flyway(owner, "39").migrate();
      jdbc.execute("CREATE ROLE limited LOGIN PASSWORD 'limited'");
      jdbc.execute("GRANT ALL ON SCHEMA public TO limited");
      jdbc.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO limited");
      final var limited = new DriverManagerDataSource(postgres.getJdbcUrl(), "limited", "limited");

      assertThatThrownBy(() -> flyway(limited, "40").migrate())
          .hasMessageContaining("may not create the extension pg_trgm");
      assertThat(extensions(jdbc)).isEmpty();
    }
  }

  private static List<String> extensions(final JdbcTemplate jdbc) {
    return jdbc.queryForList(
        "SELECT extname FROM pg_extension WHERE extname = 'pg_trgm'", String.class);
  }
}
