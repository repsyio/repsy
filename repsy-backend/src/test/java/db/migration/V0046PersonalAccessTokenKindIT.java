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

import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * V0046 (RPS-2039) on PostgreSQL: a token that exists at V0045 is PERSONAL after V0046, the new
 * column is NOT NULL, and a row can be written as TEMPORARY. It owns its container and touches no
 * Spring context.
 */
@DisplayName("V0046 adds personal_access_token.kind (PostgreSQL)")
class V0046PersonalAccessTokenKindIT {

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
  @DisplayName("an existing token becomes PERSONAL, the column is NOT NULL and takes TEMPORARY")
  void existingTokensArePersonal() {
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
    final var userId = UUID.randomUUID();

    flyway.target("45").load().migrate();
    jdbc.update(
        "insert into \"users\" (\"id\", \"username\", \"hash\", \"role\", \"created_at\")"
            + " values (?, 'kind-user', 'hash', 'USER', now())",
        userId);
    jdbc.update(
        "insert into \"personal_access_token\" (\"id\", \"user_id\", \"name\", \"token_hash\","
            + " \"scopes\", \"expiration_date\", \"created_at\")"
            + " values (?, ?, 'ci', ?, 'profile:read', now(), now())",
        UUID.randomUUID(),
        userId,
        "a".repeat(64));

    flyway.target("46").load().migrate();

    assertThat(jdbc.queryForList("select \"kind\" from \"personal_access_token\"", String.class))
        .containsExactly("PERSONAL");
    assertThat(
            jdbc.queryForObject(
                "select is_nullable from information_schema.columns"
                    + " where table_name = 'personal_access_token' and column_name = 'kind'",
                String.class))
        .isEqualTo("NO");

    jdbc.update(
        "insert into \"personal_access_token\" (\"id\", \"user_id\", \"kind\", \"name\","
            + " \"token_hash\", \"scopes\", \"expiration_date\", \"created_at\")"
            + " values (?, ?, 'TEMPORARY', 'cli', ?, 'profile:read', now(), now())",
        UUID.randomUUID(),
        userId,
        "b".repeat(64));

    assertThat(
            jdbc.queryForObject(
                "select count(*) from \"personal_access_token\" where \"kind\" = 'TEMPORARY'",
                Long.class))
        .isEqualTo(1);
    final var rejectedTokenId = UUID.randomUUID();
    final var rejectedTokenHash = "c".repeat(64);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into \"personal_access_token\" (\"id\", \"user_id\", \"kind\", \"name\","
                        + " \"token_hash\", \"scopes\", \"expiration_date\", \"created_at\")"
                        + " values (?, ?, NULL, 'x', ?, 'x', now(), now())",
                    rejectedTokenId,
                    userId,
                    rejectedTokenHash))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
