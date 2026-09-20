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

import io.repsy.os.shared.auth.utils.PasswordHasher;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.apache.commons.codec.digest.DigestUtils;
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
@DisplayName("V0017 drop users.salt (H2)")
class V0017DropUserSaltTest {

  private static final String PASSWORD = "Password1!";
  private static final String SALT = "0123456789abcdef";

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
  @DisplayName("blanks a salted SHA-256 hash and revokes the refresh tokens of that account")
  void blanksLegacyHash() throws Exception {

    final var id = UUID.randomUUID();

    migrateTo("16");
    insert(id, DigestUtils.sha256Hex(PASSWORD + SALT), 2);

    migrateTo("17");

    assertThat(hashOf(id)).isEmpty();
    assertThat(tokenVersionOf(id)).isEqualTo(3);
  }

  @Test
  @DisplayName("leaves a BCrypt hash and its refresh tokens alone")
  void leavesBcryptAlone() throws Exception {

    final var id = UUID.randomUUID();
    final var hash = PasswordHasher.hash(PASSWORD);

    migrateTo("16");
    insert(id, hash, 2);

    migrateTo("17");

    assertThat(hashOf(id)).isEqualTo(hash);
    assertThat(tokenVersionOf(id)).isEqualTo(2);
    assertThat(PasswordHasher.matches(PASSWORD, hashOf(id))).isTrue();
  }

  @Test
  @DisplayName("treats every account on its own")
  void handlesEveryRow() throws Exception {

    final var legacy = UUID.randomUUID();
    final var bcrypt = UUID.randomUUID();
    final var reset = UUID.randomUUID();
    final var hash = PasswordHasher.hash(PASSWORD);

    migrateTo("16");
    insert(legacy, DigestUtils.sha256Hex(PASSWORD + SALT), 0);
    insert(bcrypt, hash, 0);
    insert(reset, "", 0);

    migrateTo("17");

    assertThat(hashOf(legacy)).isEmpty();
    assertThat(hashOf(bcrypt)).isEqualTo(hash);
    assertThat(hashOf(reset)).isEmpty();
  }

  @Test
  @DisplayName("drops the salt column")
  void dropsSaltColumn() throws Exception {

    migrateTo("16");
    try (final var connection = connect();
        final var statement = connection.createStatement()) {
      statement.execute("select \"salt\" from \"public\".\"users\"");
    }

    migrateTo("17");

    try (final var connection = connect();
        final var statement = connection.createStatement()) {
      assertThatThrownBy(() -> statement.execute("select \"salt\" from \"public\".\"users\""))
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  @DisplayName("runs on a database without any user")
  void runsOnEmptyTable() {
    migrateTo("17");
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

  private void insert(final UUID id, final String hash, final int tokenVersion)
      throws SQLException {
    try (final var connection = connect();
        final var insert =
            connection.prepareStatement(
                "insert into \"public\".\"users\""
                    + " (\"id\", \"username\", \"hash\", \"salt\", \"role\", \"created_at\","
                    + " \"token_version\") values (?, ?, ?, ?, ?, ?, ?)")) {
      insert.setObject(1, id);
      insert.setString(2, "u" + id.toString().substring(0, 8));
      insert.setString(3, hash);
      insert.setString(4, SALT);
      insert.setString(5, "USER");
      insert.setTimestamp(6, Timestamp.from(Instant.now()));
      insert.setInt(7, tokenVersion);
      insert.executeUpdate();
    }
  }

  private String hashOf(final UUID id) throws SQLException {
    return columnOf(id, "hash", ResultSet::getString);
  }

  private int tokenVersionOf(final UUID id) throws SQLException {
    return columnOf(id, "token_version", ResultSet::getInt);
  }

  private <T> T columnOf(final UUID id, final String column, final RowReader<T> reader)
      throws SQLException {
    try (final var connection = connect();
        final var select =
            connection.prepareStatement(
                "select \"" + column + "\" from \"public\".\"users\" where \"id\" = ?")) {
      select.setObject(1, id);
      try (final var rows = select.executeQuery()) {
        rows.next();
        return reader.read(rows, 1);
      }
    }
  }

  private Connection connect() throws SQLException {
    return this.dataSource.getConnection();
  }

  @FunctionalInterface
  private interface RowReader<T> {
    T read(ResultSet rows, int column) throws SQLException;
  }
}
