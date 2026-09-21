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

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Runs the real migrations against an in-memory H2 database, so it needs no Docker. The PostgreSQL
 * script has the same statements, and every integration test runs it against a real PostgreSQL.
 */
@DisplayName("V0018 cascade refresh_tokens on user delete (H2)")
class V0018CascadeRefreshTokensTest {

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

  @Test
  @DisplayName("removes the tokens of users that no longer exist and keeps the others")
  void removesOrphans() throws Exception {

    final var user = UUID.randomUUID();
    final var kept = UUID.randomUUID();
    final var orphan = UUID.randomUUID();

    migrateTo("17");
    insertUser(user);
    insertToken(kept, user);
    insertToken(orphan, UUID.randomUUID());

    migrateTo("18");

    assertThat(tokenExists(kept)).isTrue();
    assertThat(tokenExists(orphan)).isFalse();
  }

  @Test
  @DisplayName("deletes the tokens of a deleted user and only those")
  void cascadesOnUserDelete() throws Exception {

    final var deleted = UUID.randomUUID();
    final var other = UUID.randomUUID();
    final var deletedToken = UUID.randomUUID();
    final var otherToken = UUID.randomUUID();

    migrateTo("18");
    insertUser(deleted);
    insertUser(other);
    insertToken(deletedToken, deleted);
    insertToken(UUID.randomUUID(), deleted);
    insertToken(otherToken, other);

    execute("delete from \"public\".\"users\" where \"id\" = '" + deleted + "'");

    assertThat(tokenExists(deletedToken)).isFalse();
    assertThat(tokenCountOf(deleted)).isZero();
    assertThat(tokenExists(otherToken)).isTrue();
  }

  @Test
  @DisplayName("rejects a token for a user that does not exist")
  void rejectsOrphanInsert() throws Exception {

    migrateTo("18");

    assertThatThrownBy(() -> insertToken(UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(SQLException.class);
  }

  @Test
  @DisplayName("runs on a database without any token")
  void runsOnEmptyTable() {
    migrateTo("18");
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

  private void insertUser(final UUID id) throws SQLException {
    try (final var connection = connect();
        final var insert =
            connection.prepareStatement(
                "insert into \"public\".\"users\""
                    + " (\"id\", \"username\", \"hash\", \"role\", \"created_at\","
                    + " \"token_version\") values (?, ?, ?, ?, ?, ?)")) {
      insert.setObject(1, id);
      insert.setString(2, "u" + id.toString().substring(0, 8));
      insert.setString(3, "");
      insert.setString(4, "USER");
      insert.setTimestamp(5, Timestamp.from(Instant.now()));
      insert.setInt(6, 0);
      insert.executeUpdate();
    }
  }

  private void insertToken(final UUID id, final UUID userId) throws SQLException {
    try (final var connection = connect();
        final var insert =
            connection.prepareStatement(
                "insert into \"public\".\"refresh_tokens\""
                    + " (\"id\", \"user_id\", \"family_id\", \"expires_at\")"
                    + " values (?, ?, ?, ?)")) {
      insert.setObject(1, id);
      insert.setObject(2, userId);
      insert.setObject(3, UUID.randomUUID());
      insert.setTimestamp(4, Timestamp.from(Instant.now().plusSeconds(3600)));
      insert.executeUpdate();
    }
  }

  private void execute(final String sql) throws SQLException {
    try (final var connection = connect();
        final var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private boolean tokenExists(final UUID id) throws SQLException {
    return count("\"id\" = ?", id) == 1;
  }

  private long tokenCountOf(final UUID userId) throws SQLException {
    return count("\"user_id\" = ?", userId);
  }

  private long count(final String condition, final UUID value) throws SQLException {
    try (final var connection = connect();
        final var select =
            connection.prepareStatement(
                "select count(*) from \"public\".\"refresh_tokens\" where " + condition)) {
      select.setObject(1, value);
      try (final var rows = select.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  private Connection connect() throws SQLException {
    return this.dataSource.getConnection();
  }
}
