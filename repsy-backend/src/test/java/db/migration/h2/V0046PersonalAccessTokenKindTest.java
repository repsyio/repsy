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

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * V0046 (RPS-2039) against an in-memory H2 database: a token that exists at V0045 is PERSONAL after
 * V0046, the new column is NOT NULL, and a row can be written as TEMPORARY. {@code
 * V0046PersonalAccessTokenKindIT} proves the PostgreSQL twin.
 */
@DisplayName("V0046 adds personal_access_token.kind (H2)")
class V0046PersonalAccessTokenKindTest {

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
    Flyway.configure()
        .dataSource(this.dataSource)
        .locations("classpath:db/migration/h2")
        .schemas("public")
        .defaultSchema("public")
        .target(version)
        .load()
        .migrate();
  }

  private void insertToken(final JdbcTemplate jdbc, final UUID userId, final String hash) {
    jdbc.update(
        "insert into \"personal_access_token\" (\"id\", \"user_id\", \"name\", \"token_hash\","
            + " \"scopes\", \"expiration_date\", \"created_at\")"
            + " values (?, ?, 'ci', ?, 'profile:read', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
        UUID.randomUUID(),
        userId,
        hash);
  }

  @Test
  @DisplayName("an existing token becomes PERSONAL, the column is NOT NULL and takes TEMPORARY")
  void existingTokensArePersonal() {
    final var jdbc = new JdbcTemplate(this.dataSource);
    final var userId = UUID.randomUUID();

    this.migrateTo("45");
    jdbc.execute("SET SCHEMA \"public\"");
    jdbc.update(
        "insert into \"users\" (\"id\", \"username\", \"hash\", \"role\", \"created_at\")"
            + " values (?, 'kind-user', 'hash', 'USER', CURRENT_TIMESTAMP)",
        userId);
    this.insertToken(jdbc, userId, "a".repeat(64));

    this.migrateTo("46");
    jdbc.execute("SET SCHEMA \"public\"");

    assertThat(jdbc.queryForList("select \"kind\" from \"personal_access_token\"", String.class))
        .containsExactly("PERSONAL");
    assertThat(
            jdbc.queryForObject(
                "select is_nullable from information_schema.columns"
                    + " where lower(table_name) = 'personal_access_token' and lower(column_name) = 'kind'",
                String.class))
        .isEqualTo("NO");

    jdbc.update(
        "insert into \"personal_access_token\" (\"id\", \"user_id\", \"kind\", \"name\","
            + " \"token_hash\", \"scopes\", \"expiration_date\", \"created_at\")"
            + " values (?, ?, 'TEMPORARY', 'cli', ?, 'profile:read', CURRENT_TIMESTAMP,"
            + " CURRENT_TIMESTAMP)",
        UUID.randomUUID(),
        userId,
        "b".repeat(64));

    assertThat(
            jdbc.queryForObject(
                "select count(*) from \"personal_access_token\" where \"kind\" = 'TEMPORARY'",
                Long.class))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into \"personal_access_token\" (\"id\", \"user_id\", \"kind\", \"name\","
                        + " \"token_hash\", \"scopes\", \"expiration_date\", \"created_at\")"
                        + " values (?, ?, NULL, 'x', ?, 'x', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    UUID.randomUUID(),
                    userId,
                    "c".repeat(64)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
