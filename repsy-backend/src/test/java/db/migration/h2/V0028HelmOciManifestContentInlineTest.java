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

import io.repsy.os.server.protocols.helm.migration.HelmManifestContentMigrationScenario;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * V0028 (RPS-1392, {@code helm_oci_manifest.content} is text, no longer a large-object OID) against
 * an in-memory H2 database, so it needs no Docker. H2 keeps the clob inline, so the script changes
 * nothing; the test pins that the manifests survive the version untouched and that the scripts of
 * both dialects stay in step. {@code HelmManifestContentMigrationIT} runs the same scenario on
 * PostgreSQL, where the rows have to be rewritten.
 */
@DisplayName("V0028 Helm OCI manifest content is text (H2)")
class V0028HelmOciManifestContentInlineTest {

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
    final var configuration =
        Flyway.configure()
            .dataSource(this.dataSource)
            .locations("classpath:db/migration/h2")
            .schemas("public")
            .defaultSchema("public");

    (version == null ? configuration : configuration.target(version)).load().migrate();
  }

  @Test
  @DisplayName("leaves the manifests of a populated database as they are")
  void migratesLegacyData() {
    final var jdbc = new JdbcTemplate(this.dataSource);
    final var scenario = new HelmManifestContentMigrationScenario(jdbc, false);
    this.migrateTo("27");
    // Flyway creates the lower-case "public" schema, which H2 does not search by default.
    jdbc.execute("SET SCHEMA \"public\"");
    scenario.seed();

    this.migrateTo(null);

    jdbc.execute("SET SCHEMA \"public\"");
    scenario.verify();
  }
}
