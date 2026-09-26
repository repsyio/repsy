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
package io.repsy.os.shared.user.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.shared.auth.utils.PasswordHasher;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * RPS-1615: an upgrade from release v26.08.4 keeps every password. The old V0017 blanked the hash
 * of every account that still had a salted SHA-256 hash and dropped {@code users.salt}; it is now a
 * no-op, and V0031 only makes the column nullable (or, for a database that ran the old script, puts
 * it back). Shared by the PostgreSQL and the H2 test.
 *
 * <p>The accounts are written at V0016, the schema of the last release, and checked at the latest
 * version.
 */
public final class LegacyPasswordMigrationScenario {

  public static final String PASSWORD = "Password1!";
  public static final String SALT = "0123456789abcdef";

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private final JdbcTemplate jdbc;

  private final UUID legacyId = UUID.randomUUID();
  private final UUID otherLegacyId = UUID.randomUUID();
  private final UUID bcryptId = UUID.randomUUID();
  private final UUID resetId = UUID.randomUUID();
  private final String bcryptHash = PasswordHasher.hash(PASSWORD);

  /**
   * @param jdbc the database
   */
  public LegacyPasswordMigrationScenario(final JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static String legacyHash(final String password, final String salt) {
    return DigestUtils.sha256Hex(password + salt);
  }

  /**
   * Writes the accounts; the schema must be at V0016. Two accounts have the SHA-256 hash of the
   * release, one has BCrypt (an instance that ran a later development build) and one the empty hash
   * of a password reset.
   */
  public void seed() {
    this.user(this.legacyId, "legacy", legacyHash(PASSWORD, SALT), SALT, 2);
    this.user(
        this.otherLegacyId,
        "otherlegacy",
        legacyHash("Other1234!", "fedcba9876543210"),
        "fedcba9876543210",
        0);
    this.user(this.bcryptId, "bcrypt", this.bcryptHash, "abcdefghijklmnop", 4);
    this.user(this.resetId, "reset", "", "abcdefghijklmnop", 1);
  }

  private void user(
      final UUID id,
      final String username,
      final String hash,
      final String salt,
      final int tokenVersion) {
    this.jdbc.update(
        "insert into \"users\" (\"id\", \"username\", \"hash\", \"salt\", \"role\", \"created_at\","
            + " \"token_version\") values (?, ?, ?, ?, 'USER', ?, ?)",
        id,
        username,
        hash,
        salt,
        Timestamp.from(T0),
        tokenVersion);
  }

  /**
   * Turns the database into what the old V0017 left behind: the hashes blanked and the column gone.
   * The migration itself cannot be replayed, since V0017 no longer does it.
   */
  public void applyOldV0017() {
    this.jdbc.update(
        "update \"users\" set \"hash\" = '', \"token_version\" = \"token_version\" + 1"
            + " where \"hash\" not like '{%'");
    this.jdbc.execute("alter table \"users\" drop column \"salt\"");
  }

  private String hashOf(final UUID id) {
    return this.jdbc.queryForObject(
        "select \"hash\" from \"users\" where \"id\" = ?", String.class, id);
  }

  private String saltOf(final UUID id) {
    return this.jdbc.queryForObject(
        "select \"salt\" from \"users\" where \"id\" = ?", String.class, id);
  }

  private int tokenVersionOf(final UUID id) {
    return this.jdbc.queryForObject(
        "select \"token_version\" from \"users\" where \"id\" = ?", Integer.class, id);
  }

  /** Checks the result for a database that never ran the old V0017; the schema must be current. */
  public void verifyKept() {
    assertThat(this.hashOf(this.legacyId)).isEqualTo(legacyHash(PASSWORD, SALT));
    assertThat(this.saltOf(this.legacyId)).isEqualTo(SALT);
    assertThat(this.tokenVersionOf(this.legacyId))
        .as("no reset, so the refresh tokens stay valid")
        .isEqualTo(2);
    assertThat(this.tokenVersionOf(this.otherLegacyId)).isZero();
    assertThat(this.hashOf(this.otherLegacyId))
        .isEqualTo(legacyHash("Other1234!", "fedcba9876543210"));

    // What the application will do with the rows it finds.
    assertThat(
            PasswordHasher.matches(
                PASSWORD, this.hashOf(this.legacyId), this.saltOf(this.legacyId)))
        .isTrue();
    assertThat(
            PasswordHasher.matches(
                "Wrong1!", this.hashOf(this.legacyId), this.saltOf(this.legacyId)))
        .isFalse();

    assertThat(this.hashOf(this.bcryptId)).isEqualTo(this.bcryptHash);
    assertThat(this.tokenVersionOf(this.bcryptId)).isEqualTo(4);
    assertThat(this.hashOf(this.resetId)).isEmpty();
    assertThat(this.tokenVersionOf(this.resetId)).isEqualTo(1);

    this.verifySaltIsNullable();
  }

  /**
   * Checks the result for a database that ran the old V0017: the column is back, empty, and nobody
   * gets a legacy hash they never had.
   */
  public void verifyColumnRestored() {
    assertThat(this.saltOf(this.legacyId)).isNull();
    assertThat(this.saltOf(this.bcryptId)).isNull();
    assertThat(this.hashOf(this.legacyId)).isEmpty();
    assertThat(this.hashOf(this.bcryptId)).isEqualTo(this.bcryptHash);

    this.verifySaltIsNullable();
  }

  /** A new account has no salt, so the column must accept null. */
  private void verifySaltIsNullable() {
    final var id = UUID.randomUUID();

    this.jdbc.update(
        "insert into \"users\" (\"id\", \"username\", \"hash\", \"role\", \"created_at\","
            + " \"token_version\") values (?, ?, ?, 'USER', ?, 0)",
        id,
        "nosalt",
        this.bcryptHash,
        Timestamp.from(T0));

    assertThat(this.saltOf(id)).isNull();
  }
}
