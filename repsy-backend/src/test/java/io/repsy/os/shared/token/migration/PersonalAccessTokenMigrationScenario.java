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
package io.repsy.os.shared.token.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V0034 adds the {@code personal_access_token} table (RPS-1901). The migration only creates a
 * table, so there is no legacy data to convert; what is checked is the table itself, on both
 * databases, because H2 and PostgreSQL each have their own copy of the script and nothing else
 * would notice if the two drifted: the columns, their types and nullability, the unique index on
 * the hash, the index on the user, and what the database enforces (not null, length, uniqueness and
 * the cascade of the foreign key). It also checks that a database migrated from V0033 keeps its
 * users. Shared by the PostgreSQL and the H2 test.
 */
public final class PersonalAccessTokenMigrationScenario {

  private static final String TABLE = "personal_access_token";
  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private final JdbcTemplate jdbc;

  private final UUID existingUserId = UUID.randomUUID();
  private final UUID otherUserId = UUID.randomUUID();

  /**
   * @param jdbc the database, at V0033 for {@link #seed()}, at the latest version for {@link
   *     #verify()}
   */
  public PersonalAccessTokenMigrationScenario(final JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static Timestamp at(final int minutes) {
    return Timestamp.from(T0.plusSeconds(minutes * 60L));
  }

  /** Writes a user, as a database that already is in use has; the schema must be at V0033. */
  public void seed() {
    this.user(this.existingUserId, "existing-user");
  }

  private void user(final UUID id, final String username) {
    this.jdbc.update(
        "insert into \"users\" (\"id\", \"username\", \"hash\", \"role\", \"created_at\")"
            + " values (?, ?, 'hash', 'USER', ?)",
        id,
        username,
        at(0));
  }

  private int token(
      final UUID id, final UUID userId, final String name, final String hash, final String scopes) {
    return this.jdbc.update(
        "insert into \"personal_access_token\" (\"id\", \"user_id\", \"name\", \"token_hash\","
            + " \"scopes\", \"expiration_date\", \"created_at\") values (?, ?, ?, ?, ?, ?, ?)",
        id,
        userId,
        name,
        hash,
        scopes,
        at(60),
        at(1));
  }

  private long count(final String where, final Object... args) {
    return this.jdbc.queryForObject(
        "select count(*) from \"personal_access_token\" where " + where, Long.class, args);
  }

  /** Checks the migrated database; the schema must be at the latest version. */
  public void verify() {
    this.verifyTheUsersSurvived();
    this.verifyTheColumns();
    this.verifyTheIndexes();
    this.verifyWhatTheDatabaseEnforces();
    this.verifyTheCascade();
  }

  private void verifyTheUsersSurvived() {
    assertThat(
            this.jdbc.queryForObject(
                "select \"username\" from \"users\" where \"id\" = ?",
                String.class,
                this.existingUserId))
        .isEqualTo("existing-user");
  }

  private void verifyTheColumns() {
    final Map<String, String> columns = new TreeMap<>();

    this.jdbc.query(
        "select column_name, data_type, character_maximum_length, is_nullable"
            + " from information_schema.columns where lower(table_name) = ?",
        rs -> {
          final var length = rs.getObject("character_maximum_length");
          final var type = rs.getString("data_type").toLowerCase(Locale.ROOT);
          final var normalized =
              type.startsWith("timestamp")
                  ? "timestamp"
                  : (length == null ? type : type + "(" + ((Number) length).longValue() + ")");

          columns.put(
              rs.getString("column_name").toLowerCase(Locale.ROOT),
              normalized
                  + ("YES".equalsIgnoreCase(rs.getString("is_nullable")) ? " null" : " not null"));
        },
        TABLE);

    final Map<String, String> expected = new TreeMap<>();

    expected.put("id", "uuid not null");
    expected.put("user_id", "uuid not null");
    expected.put("name", "character varying(150) not null");
    expected.put("token_hash", "character varying(64) not null");
    expected.put("scopes", "character varying(255) not null");
    expected.put("expiration_date", "timestamp not null");
    expected.put("last_used_at", "timestamp null");
    expected.put("created_at", "timestamp not null");

    assertThat(columns).isEqualTo(expected);
  }

  /** Index name to "unique:column", read through JDBC metadata, which both databases answer. */
  private Map<String, String> indexes() {
    return this.jdbc.execute(
        (ConnectionCallback<Map<String, String>>)
            connection -> {
              final Map<String, String> found = new HashMap<>();

              try (var rs =
                  connection.getMetaData().getIndexInfo(null, null, TABLE, false, false)) {
                while (rs.next()) {
                  final var name = rs.getString("INDEX_NAME");
                  final var column = rs.getString("COLUMN_NAME");

                  if (name != null && column != null) {
                    found.put(
                        name.toLowerCase(Locale.ROOT),
                        (rs.getBoolean("NON_UNIQUE") ? "plain:" : "unique:")
                            + column.toLowerCase(Locale.ROOT));
                  }
                }
              }

              return found;
            });
  }

  private void verifyTheIndexes() {
    final var indexes = this.indexes();

    assertThat(indexes)
        .containsEntry("ux_personal_access_token__token_hash", "unique:token_hash")
        .containsEntry("idx_personal_access_token__user_id", "plain:user_id");
  }

  private void verifyWhatTheDatabaseEnforces() {
    final var id = UUID.randomUUID();
    final var hash = "a".repeat(64);

    assertThat(this.token(id, this.existingUserId, "ci", hash, "profile:read")).isEqualTo(1);

    // The hash is unique: two tokens never share one, whoever owns them.
    this.user(this.otherUserId, "other-user");
    assertThatThrownBy(() -> this.token(UUID.randomUUID(), this.otherUserId, "ci", hash, "x"))
        .isInstanceOf(DataIntegrityViolationException.class);

    // The same name, twice, for one user is fine: names are not unique.
    assertThat(this.token(UUID.randomUUID(), this.existingUserId, "ci", "b".repeat(64), "x"))
        .isEqualTo(1);

    // A token belongs to a user that exists.
    assertThatThrownBy(
            () -> this.token(UUID.randomUUID(), UUID.randomUUID(), "ci", "c".repeat(64), "x"))
        .isInstanceOf(DataIntegrityViolationException.class);

    // Each column is as wide as the entity says.
    assertThatThrownBy(
            () ->
                this.token(
                    UUID.randomUUID(), this.existingUserId, "n".repeat(151), "d".repeat(64), "x"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> this.token(UUID.randomUUID(), this.existingUserId, "ci", "e".repeat(65), "x"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                this.token(
                    UUID.randomUUID(), this.existingUserId, "ci", "f".repeat(64), "s".repeat(256)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(
            this.token(
                UUID.randomUUID(),
                this.existingUserId,
                "n".repeat(150),
                "0".repeat(64),
                "s".repeat(255)))
        .isEqualTo(1);

    // Not null: every column but last_used_at.
    for (final var column :
        new String[] {"user_id", "name", "token_hash", "scopes", "expiration_date", "created_at"}) {
      assertThatThrownBy(() -> this.insertWithNull(column))
          .as("a null %s", column)
          .isInstanceOf(DataIntegrityViolationException.class);
    }

    this.jdbc.update(
        "insert into \"personal_access_token\" (\"id\", \"user_id\", \"name\", \"token_hash\","
            + " \"scopes\", \"expiration_date\", \"created_at\", \"last_used_at\")"
            + " values (?, ?, 'ci', ?, 'x', ?, ?, null)",
        UUID.randomUUID(),
        this.existingUserId,
        "9".repeat(64),
        at(60),
        at(1));
  }

  private void insertWithNull(final String nullColumn) {
    final Map<String, Object> values = new TreeMap<>();

    values.put("id", UUID.randomUUID());
    values.put("user_id", this.existingUserId);
    values.put("name", "ci");
    values.put("token_hash", UUID.randomUUID().toString().replace("-", "") + "0".repeat(32));
    values.put("scopes", "x");
    values.put("expiration_date", at(60));
    values.put("created_at", at(1));
    values.put(nullColumn, null);

    final var columns = String.join("\", \"", values.keySet());
    final var marks = String.join(", ", java.util.Collections.nCopies(values.size(), "?"));

    this.jdbc.update(
        "insert into \"personal_access_token\" (\"" + columns + "\") values (" + marks + ")",
        values.values().toArray());
  }

  private void verifyTheCascade() {
    final var otherToken = UUID.randomUUID();

    this.token(otherToken, this.otherUserId, "others", "7".repeat(64), "x");
    assertThat(this.count("\"user_id\" = ?", this.existingUserId)).isPositive();

    this.jdbc.update("delete from \"users\" where \"id\" = ?", this.existingUserId);

    assertThat(this.count("\"user_id\" = ?", this.existingUserId))
        .as("the tokens of a deleted user")
        .isZero();
    assertThat(this.count("\"id\" = ?", otherToken)).as("the token of another user").isEqualTo(1);
  }
}
