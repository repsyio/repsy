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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * V0035 (RPS-2112) against an in-memory H2 database: the four foreign key columns without an index
 * get one. {@code V0035IndexCargoM2mReverseIT} proves the PostgreSQL twin (built CONCURRENTLY).
 */
@DisplayName("V0035 indexes the cargo join table reverse sides and key_store keyserver (H2)")
class V0035IndexCargoM2mReverseTest {

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

  private List<String> indexNames() {
    return new JdbcTemplate(this.dataSource)
        .queryForList(
            "SELECT index_name FROM information_schema.indexes WHERE table_schema = 'public'",
            String.class);
  }

  @Test
  @DisplayName("creates the four indexes, none exists at V0034")
  void createsIndexes() {
    final var expected =
        List.of(
            "idx_cargo_crate_author__author_id",
            "idx_cargo_crate_keyword__keyword_id",
            "idx_cargo_crate_category__category_id",
            "idx_key_store__allowed_keyserver_id");

    this.migrateTo("34");
    assertThat(this.indexNames()).doesNotContainAnyElementsOf(expected);

    this.migrateTo(null);
    assertThat(this.indexNames()).containsAll(expected);
  }
}
