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

import java.sql.Timestamp;
import java.time.Instant;
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
 * Runs the real migrations against an in-memory H2 database, so it needs no Docker. The PostgreSQL
 * script has the same statements but for the uuid function, and every integration test runs it
 * against a real PostgreSQL.
 */
@DisplayName("V0023 Maven PGP signature settings and signature tracking (H2)")
class V0023AddMavenPgpSignatureSettingsTest {

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

  private UUID insertRepo(final String name) {
    final var id = UUID.randomUUID();
    this.jdbc.update(
        "insert into \"public\".\"repo\" (\"id\", \"name\", \"type\", \"created_at\") values (?, ?, 'MAVEN', ?)",
        id,
        name,
        Timestamp.from(Instant.now()));
    return id;
  }

  private UUID insertArtifact(final UUID repoId, final String artifactName) {
    final var id = UUID.randomUUID();
    this.jdbc.update(
        "insert into \"public\".\"maven_artifact\" (\"id\", \"repo_id\", \"group_name\", \"artifact_name\")"
            + " values (?, ?, 'com.acme', ?)",
        id,
        repoId,
        artifactName);
    return id;
  }

  private UUID insertVersion(
      final UUID artifactId, final String versionName, final Boolean signed) {
    final var id = UUID.randomUUID();
    this.jdbc.update(
        "insert into \"public\".\"maven_artifact_version\" (\"id\", \"artifact_id\", \"type\","
            + " \"version_name\", \"signed\") values (?, ?, 'RELEASE', ?, ?)",
        id,
        artifactId,
        versionName,
        signed);
    return id;
  }

  private List<String> signatureFileNames() {
    return this.jdbc.queryForList(
        "select \"file_name\" from \"public\".\"maven_version_signature\" order by \"file_name\"",
        String.class);
  }

  @Test
  @DisplayName("records the .pom of every signed release version and nothing else")
  void backfillsTheSignedReleasePoms() {
    this.migrateTo("22");
    final var artifact = this.insertArtifact(this.insertRepo("mvn"), "lib");
    final var signed = this.insertVersion(artifact, "1.0", true);
    this.insertVersion(artifact, "2.0", false);
    this.insertVersion(artifact, "3.0", null);
    this.insertVersion(artifact, "4.0-SNAPSHOT", true);

    this.migrateTo("23");

    assertThat(this.signatureFileNames()).containsExactly("lib-1.0.pom");
    assertThat(
            this.jdbc.queryForObject(
                "select \"artifact_version_id\" from \"public\".\"maven_version_signature\"",
                UUID.class))
        .isEqualTo(signed);
  }

  @Test
  @DisplayName("adds the two settings to every repo with their defaults")
  void addsTheSettingsWithTheirDefaults() {
    this.migrateTo("22");
    final var repo = this.insertRepo("mvn");

    this.migrateTo("23");

    assertThat(
            this.jdbc.queryForMap(
                "select \"pgp_verify_all_signatures_enabled\" as v,"
                    + " \"pgp_key_server_lookup_enabled\" as l from \"public\".\"repo\" where \"id\" = ?",
                repo))
        .containsEntry("V", false)
        .containsEntry("L", true);
  }

  @Test
  @DisplayName("deletes the rows of a version with it, and allows one row per file")
  void theRowsGoWithTheirVersion() {
    this.migrateTo("23");
    final var artifact = this.insertArtifact(this.insertRepo("mvn"), "lib");
    final var version = this.insertVersion(artifact, "1.0", false);
    this.jdbc.update(
        "insert into \"public\".\"maven_version_signature\" (\"id\", \"artifact_version_id\", \"file_name\","
            + " \"verified_at\") values (?, ?, 'lib-1.0.jar', ?)",
        UUID.randomUUID(),
        version,
        Timestamp.from(Instant.now()));

    assertThat(this.signatureFileNames()).containsExactly("lib-1.0.jar");

    this.jdbc.update("delete from \"public\".\"maven_artifact_version\" where \"id\" = ?", version);

    assertThat(this.signatureFileNames()).isEmpty();
  }

  @Test
  @DisplayName("runs on a database without any version")
  void runsOnAnEmptyDatabase() {
    this.migrateTo("23");

    assertThat(this.signatureFileNames()).isEmpty();
  }
}
