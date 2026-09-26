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
package io.repsy.os.server.protocols.cargo.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V0030 adds {@code cargo_crate_index.created_at} (RPS-1605) and backfills it from the publish time
 * {@code cargo_crate_meta} keeps for the same version, falling back to the crate's own time for an
 * index row that has no meta row. Shared by the PostgreSQL and the H2 test.
 */
public final class CargoIndexCreatedAtMigrationScenario {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private final JdbcTemplate jdbc;

  private final UUID repoId = UUID.randomUUID();
  private final UUID crateId = UUID.randomUUID();
  private final UUID otherCrateId = UUID.randomUUID();

  /**
   * @param jdbc the database, at V0029
   */
  public CargoIndexCreatedAtMigrationScenario(final JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static Timestamp at(final int minutes) {
    return Timestamp.from(T0.plusSeconds(minutes * 60L));
  }

  /**
   * Writes the legacy data; the schema must be at V0029. The versions are inserted in the reverse
   * of their publish order, and one of them is yanked, so nothing but the meta timestamp says which
   * came first.
   */
  public void seed() {
    this.jdbc.update(
        "insert into \"repo\" (\"id\", \"name\", \"type\", \"created_at\") values (?, ?, 'CARGO', ?)",
        this.repoId,
        "cargomigration",
        at(0));
    this.crate(this.crateId, "legacy_crate", 5);
    this.crate(this.otherCrateId, "other_crate", 50);

    this.version(this.crateId, "legacy_crate", "3.0.0", 30, false, true);
    this.version(this.crateId, "legacy_crate", "1.0.0", 10, true, true);
    this.version(this.crateId, "legacy_crate", "2.0.0", 20, false, true);
    // An index row with no meta row: the crate's own time is the fallback.
    this.version(this.otherCrateId, "other_crate", "1.0.0", 0, false, false);
  }

  private void crate(final UUID id, final String name, final int minutes) {
    this.jdbc.update(
        "insert into \"cargo_crate\" (\"id\", \"repo_id\", \"name\", \"created_at\", \"has_lib\")"
            + " values (?, ?, ?, ?, true)",
        id,
        this.repoId,
        name,
        at(minutes));
  }

  private void version(
      final UUID crate,
      final String name,
      final String vers,
      final int minutes,
      final boolean yanked,
      final boolean withMeta) {
    this.jdbc.update(
        "insert into \"cargo_crate_index\" (\"id\", \"crate_id\", \"name\", \"vers\", \"cksum\","
            + " \"yanked\") values (?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        crate,
        name,
        vers,
        "0".repeat(64),
        yanked);

    if (withMeta) {
      this.jdbc.update(
          "insert into \"cargo_crate_meta\" (\"id\", \"crate_id\", \"version\", \"created_at\")"
              + " values (?, ?, ?, ?)",
          UUID.randomUUID(),
          crate,
          vers,
          at(minutes));
    }
  }

  private Timestamp createdAt(final UUID crate, final String vers) {
    return this.jdbc.queryForObject(
        "select \"created_at\" from \"cargo_crate_index\" where \"crate_id\" = ? and \"vers\" = ?",
        Timestamp.class,
        crate,
        vers);
  }

  /** Checks the result; the schema must be at the latest version. */
  public void verify() {
    assertThat(this.createdAt(this.crateId, "1.0.0")).isEqualTo(at(10));
    assertThat(this.createdAt(this.crateId, "2.0.0")).isEqualTo(at(20));
    assertThat(this.createdAt(this.crateId, "3.0.0")).isEqualTo(at(30));
    assertThat(this.createdAt(this.otherCrateId, "1.0.0")).isEqualTo(at(50));

    final List<String> ordered =
        this.jdbc.queryForList(
            "select \"vers\" from \"cargo_crate_index\" where \"crate_id\" = ?"
                + " order by \"created_at\", \"id\"",
            String.class,
            this.crateId);
    assertThat(ordered).containsExactly("1.0.0", "2.0.0", "3.0.0");

    // The other columns are untouched.
    assertThat(
            this.jdbc.queryForObject(
                "select \"yanked\" from \"cargo_crate_index\" where \"crate_id\" = ?"
                    + " and \"vers\" = '1.0.0'",
                Boolean.class,
                this.crateId))
        .isTrue();
    assertThat(
            this.jdbc.queryForObject(
                "select count(*) from \"cargo_crate_index\" where \"created_at\" is null",
                Long.class))
        .isZero();
  }
}
