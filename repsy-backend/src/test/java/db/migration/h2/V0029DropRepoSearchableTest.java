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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * V0029 (RPS-1427, {@code repo.searchable} is dropped) against an in-memory H2 database, so it
 * needs no Docker. The PostgreSQL script has the same statement, and every integration test runs it
 * against a real PostgreSQL.
 */
@DisplayName("V0029 drop repo.searchable (H2)")
class V0029DropRepoSearchableTest {

  private SingleConnectionDataSource dataSource;
  private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    this.dataSource =
        new SingleConnectionDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "sa",
            "",
            true);
    this.jdbc = new JdbcTemplate(this.dataSource);
  }

  @AfterEach
  void tearDown() {
    this.dataSource.destroy();
  }

  private void migrateTo(final String version) {
    Flyway.configure()
        .dataSource(this.dataSource)
        .locations("classpath:db/migration/h2")
        .schemas("public")
        .defaultSchema("public")
        .target(version)
        .load()
        .migrate();
  }

  private UUID insertRepo(final String type, final boolean searchable) {
    final var id = UUID.randomUUID();
    this.jdbc.update(
        "insert into \"public\".\"repo\" (\"id\", \"name\", \"type\", \"searchable\","
            + " \"allow_override\", \"created_at\") values (?, ?, ?, ?, true, ?)",
        id,
        "r" + id.toString().substring(0, 8),
        type,
        searchable,
        Timestamp.from(Instant.now()));
    return id;
  }

  @Test
  @DisplayName("keeps the repos of a populated database and drops the column")
  void keepsRowsAndDropsColumn() {
    migrateTo("28");
    final var maven = insertRepo("MAVEN", false);
    final var cargo = insertRepo("CARGO", true);

    migrateTo("29");

    assertThat(
            this.jdbc.queryForList(
                "select \"id\" from \"public\".\"repo\" order by \"name\"", UUID.class))
        .containsExactlyInAnyOrder(maven, cargo);
    assertThat(
            this.jdbc.queryForObject(
                "select \"allow_override\" from \"public\".\"repo\" where \"id\" = ?",
                Boolean.class,
                cargo))
        .isTrue();
    assertThatThrownBy(
            () -> this.jdbc.queryForList("select \"searchable\" from \"public\".\"repo\""))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  @DisplayName("runs on a database without any repo")
  void runsOnEmptyTable() {
    migrateTo("29");

    assertThat(this.jdbc.queryForObject("select count(*) from \"public\".\"repo\"", Long.class))
        .isZero();
  }
}
