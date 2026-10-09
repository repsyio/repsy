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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import db.migration.MigrationConventionChecker.Violation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fails the build when a migration breaks the safe migration convention (RPS-2119, "Database" in
 * AGENTS.md).
 */
class MigrationConventionTest {

  private static final Path RESOURCES = Path.of("src/main/resources/db/migration");
  private static final Path POSTGRESQL = RESOURCES.resolve("postgresql");
  private static final Path H2 = RESOURCES.resolve("h2");
  private static final Path H2_JAVA = Path.of("src/main/java/db/migration/h2");

  /**
   * Violations of migrations that were merged before the check existed, as {@code file:rule}. The
   * set may only shrink: an entry that no longer matches a violation fails the test, so fix the
   * file or delete the entry, never add one. A new migration states a deliberate exception inside
   * the script with {@code -- migration-check: allow <rule> <reason>}.
   */
  private static final Set<String> EXISTING_VIOLATIONS =
      Set.of(
          "V0002__Golang_Protocol.sql:lockTimeout",
          "V0003__Init_Cargo_Support.sql:lockTimeout",
          "V0004__Drop_Deleted_Column.sql:lockTimeout",
          "V0005__Init_NuGet_Support.sql:lockTimeout",
          "V0006__Init_Helm_Support.sql:lockTimeout",
          "V0007__Allowed_Keyserver.sql:indexConcurrently",
          "V0007__Allowed_Keyserver.sql:lockTimeout",
          "V0008__Init_Ruby_Support.sql:lockTimeout",
          "V0010__Add_Repo_Security_Scan_Enabled.sql:lockTimeout",
          "V0011__Widen_Vulnerability_Scan_Active_Statuses.sql:indexConcurrently",
          "V0013__Add_User_Token_Version.sql:lockTimeout",
          "V0014__Convert_NuGet_Dependencies_To_Json.sql:h2Twin",
          "V0018__Cascade_Refresh_Tokens_On_User_Delete.sql:indexConcurrently",
          "V0018__Cascade_Refresh_Tokens_On_User_Delete.sql:lockTimeout",
          "V0021__Go_Module_Path_Case_Sensitive.sql:indexConcurrently",
          "V0022__Widen_Vulnerability_Scan_Artifact_Version.sql:lockTimeout",
          "V0022__Widen_Vulnerability_Scan_Artifact_Version.sql:tableRewrite",
          "V0023__Add_Maven_Pgp_Signature_Settings.sql:lockTimeout",
          "V0024__Docker_Content_Addressed_Manifests.sql:indexConcurrently",
          "V0024__Docker_Content_Addressed_Manifests.sql:lockTimeout",
          "V0027__Add_Vulnerability_Finding_Package_Index.sql:indexConcurrently",
          "V0029__Drop_Repo_Searchable.sql:lockTimeout",
          "V0030__Cargo_Crate_Index_Created_At.sql:lockTimeout",
          "V0031__Restore_User_Salt.sql:lockTimeout",
          "V0032__Helm_Chart_Version_Api_Version_And_Dependencies.sql:lockTimeout");

  @Test
  @DisplayName("new migrations keep the safe migration convention")
  void newMigrationsKeepTheConvention() {
    Set<String> actual = keys(MigrationConventionChecker.check(POSTGRESQL, H2, H2_JAVA));

    Set<String> unexpected = new TreeSet<>(actual);
    unexpected.removeAll(EXISTING_VIOLATIONS);
    assertTrue(
        unexpected.isEmpty(),
        () ->
            "New migration violations (see the migration convention in AGENTS.md; do not extend the allow-list): "
                + unexpected);
  }

  @Test
  @DisplayName("the allow-list of old migrations has no stale entries")
  void allowListHasNoStaleEntries() {
    Set<String> actual = keys(MigrationConventionChecker.check(POSTGRESQL, H2, H2_JAVA));

    Set<String> stale = new TreeSet<>(EXISTING_VIOLATIONS);
    stale.removeAll(actual);
    assertTrue(stale.isEmpty(), () -> "Remove from EXISTING_VIOLATIONS, they are fixed: " + stale);
  }

  @Test
  @DisplayName("the check flags planted drift and accepts the safe form")
  void flagsPlantedDrift(@TempDir Path dir) throws IOException {
    Path pg = Files.createDirectories(dir.resolve("postgresql"));
    Path h2 = Files.createDirectories(dir.resolve("h2"));
    // blocking index on an existing table, no twin
    write(pg, "V0001__Blocking_Index.sql", "CREATE INDEX ix_a ON repo (name);");
    // CONCURRENTLY without .sql.conf, mixed with another statement
    write(
        pg,
        "V0002__No_Conf.sql",
        "CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_b ON repo (id);\nUPDATE repo SET x = 1 WHERE id = 1;");
    // lock without lock_timeout, rewrite and unbatched update
    write(
        pg,
        "V0003__Lock.sql",
        "ALTER TABLE repo ADD COLUMN y integer;\nALTER TABLE repo ALTER COLUMN z TYPE text;\nUPDATE repo SET y = 1;");
    write(h2, "V0003__Lock.sql", "CREATE INDEX CONCURRENTLY ix ON repo (id);");
    // the safe forms
    write(
        pg, "V0004__Safe_Index.sql", "CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_c ON repo (id);");
    write(pg, "V0004__Safe_Index.sql.conf", "executeInTransaction=false\n");
    write(h2, "V0004__Safe_Index.sql", "CREATE INDEX IF NOT EXISTS ix_c ON repo (id);");
    write(
        pg,
        "V0005__Safe_Alter.sql",
        "SET lock_timeout = '5s';\nALTER TABLE repo ADD COLUMN w integer;\n"
            + "CREATE TABLE fresh (id bigint);\nCREATE INDEX ix_d ON fresh (id);");
    write(h2, "V0005__Safe_Alter.sql", "ALTER TABLE repo ADD COLUMN w integer;");
    write(
        pg,
        "V0006__Marked.sql",
        "-- migration-check: allow unbatchedUpdate table has 3 rows\nUPDATE settings SET v = 1;");
    write(h2, "V0006__Marked.sql", "UPDATE settings SET v = 1;");
    write(h2, "V0007__Orphan.sql", "SELECT 1;");

    Set<String> found = keys(MigrationConventionChecker.check(pg, h2, null));

    assertEquals(
        new TreeSet<>(
            Set.of(
                "V0001__Blocking_Index.sql:indexConcurrently",
                "V0001__Blocking_Index.sql:h2Twin",
                "V0002__No_Conf.sql:concurrentlyConf",
                "V0002__No_Conf.sql:concurrentlyOnly",
                "V0002__No_Conf.sql:h2Twin",
                "V0003__Lock.sql:lockTimeout",
                "V0003__Lock.sql:tableRewrite",
                "V0003__Lock.sql:unbatchedUpdate",
                "V0003__Lock.sql:h2Syntax",
                "V0007__Orphan.sql:h2Twin")),
        found);
  }

  private static Set<String> keys(List<Violation> violations) {
    return violations.stream().map(Violation::key).collect(Collectors.toCollection(TreeSet::new));
  }

  private static void write(Path dir, String name, String content) throws IOException {
    Files.writeString(dir.resolve(name), content);
  }
}
