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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Runs the real migrations against an in-memory H2 database, so it needs no Docker. The PostgreSQL
 * script has the same statements, and every integration test runs it against a real PostgreSQL.
 */
@DisplayName("V0025 Maven pending signature (H2)")
class V0025AddMavenPendingSignatureTest {

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
    Flyway.configure()
        .dataSource(this.dataSource)
        .locations("classpath:db/migration/h2")
        .schemas("public")
        .defaultSchema("public")
        .target("25")
        .load()
        .migrate();
  }

  @AfterEach
  void tearDown() {
    this.dataSource.destroy();
  }

  private UUID insertRepo() {
    final var id = UUID.randomUUID();
    this.jdbc.update(
        "insert into \"public\".\"repo\" (\"id\", \"name\", \"type\", \"created_at\")"
            + " values (?, ?, 'MAVEN', ?)",
        id,
        "r" + id.toString().substring(0, 8),
        Timestamp.from(Instant.now()));
    return id;
  }

  private void insertPending(final UUID repoId, final String path) {
    this.jdbc.update(
        "insert into \"public\".\"maven_pending_signature\" (\"id\", \"repo_id\","
            + " \"signed_file_path\", \"armored_signature\", \"key_id\", \"created_at\")"
            + " values (?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        repoId,
        path,
        "-----BEGIN PGP SIGNATURE-----",
        "0123456789ABCDEF",
        Timestamp.from(Instant.now()));
  }

  private long count() {
    return this.jdbc.queryForObject(
        "select count(*) from \"public\".\"maven_pending_signature\"", Long.class);
  }

  @Test
  @DisplayName("keeps one parked signature per file of a repo, and the same path in another repo")
  void oneSignaturePerFileAndRepo() {
    final var repo = this.insertRepo();
    this.insertPending(repo, "com/acme/lib/1.0/lib-1.0.jar");
    this.insertPending(repo, "com/acme/lib/1.0/lib-1.0-sources.jar");
    this.insertPending(this.insertRepo(), "com/acme/lib/1.0/lib-1.0.jar");

    assertThat(this.count()).isEqualTo(3);
    assertThatThrownBy(() -> this.insertPending(repo, "com/acme/lib/1.0/lib-1.0.jar"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("deletes the parked signatures of a repo with it, and only those")
  void theRowsGoWithTheirRepo() {
    final var deleted = this.insertRepo();
    final var kept = this.insertRepo();
    this.insertPending(deleted, "com/acme/lib/1.0/lib-1.0.jar");
    this.insertPending(kept, "com/acme/lib/1.0/lib-1.0.jar");

    this.jdbc.update("delete from \"public\".\"repo\" where \"id\" = ?", deleted);

    assertThat(this.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("holds a whole armored signature (text) and refuses a repo that does not exist")
  void holdsTextAndNeedsARepo() {
    assertThatThrownBy(() -> this.insertPending(UUID.randomUUID(), "a/b/1/b-1.jar"))
        .isInstanceOf(DataIntegrityViolationException.class);

    final var repo = this.insertRepo();
    this.jdbc.update(
        "insert into \"public\".\"maven_pending_signature\" (\"id\", \"repo_id\","
            + " \"signed_file_path\", \"armored_signature\", \"key_id\", \"created_at\")"
            + " values (?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        repo,
        "a/b/1/b-1.jar",
        "x".repeat(70_000),
        "0123456789ABCDEF",
        Timestamp.from(Instant.now()));

    assertThat(this.count()).isEqualTo(1);
  }
}
