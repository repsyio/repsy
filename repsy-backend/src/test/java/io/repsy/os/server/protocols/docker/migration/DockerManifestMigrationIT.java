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
package io.repsy.os.server.protocols.docker.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * V0024 (RPS-1216, content-addressed Docker manifests) on a populated PostgreSQL database: the
 * legacy data of {@link DockerManifestMigrationScenario} is written at V0023, migrated to the
 * latest version and checked. It owns its container and touches no Spring context, so it neither
 * shares nor disturbs the database the other integration tests use; the same scenario runs on H2 in
 * {@code V0024DockerContentAddressedManifestsTest}.
 */
@DisplayName("V0024 content-addressed Docker manifests (PostgreSQL)")
class DockerManifestMigrationIT {

  private static PostgreSQLContainer<?> postgres;
  private static DriverManagerDataSource dataSource;

  @BeforeAll
  static void startDatabase() {
    postgres =
        new PostgreSQLContainer<>("postgres:18")
            .withDatabaseName("repsy")
            .withUsername("repsy")
            .withPassword("repsy123");
    postgres.start();
    dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  @AfterAll
  static void stopDatabase() {
    postgres.stop();
  }

  private static void migrateTo(final String version) {
    final var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/postgresql")
            .schemas("public")
            .defaultSchema("public");

    (version == null ? configuration : configuration.target(version)).load().migrate();
  }

  @Test
  @DisplayName("migrates the legacy shapes to one manifest per image and digest")
  void migratesLegacyData() {
    final var scenario = new DockerManifestMigrationScenario(new JdbcTemplate(dataSource));
    migrateTo("23");
    scenario.seed();

    migrateTo(null);

    scenario.verify();
  }
}
