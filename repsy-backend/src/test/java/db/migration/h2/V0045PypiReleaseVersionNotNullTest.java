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

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * V0045 (RPS-2198) against an in-memory H2 database: pypi_release.version is nullable at V0044 and
 * NOT NULL after V0045. {@code V0045PypiReleaseVersionNotNullIT} proves the PostgreSQL twin.
 */
@DisplayName("V0045 makes pypi_release.version NOT NULL (H2)")
class V0045PypiReleaseVersionNotNullTest {

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
    Flyway.configure()
        .dataSource(this.dataSource)
        .locations("classpath:db/migration/h2")
        .schemas("public")
        .defaultSchema("public")
        .target(version)
        .load()
        .migrate();
  }

  private String isNullable() {
    return new JdbcTemplate(this.dataSource)
        .queryForObject(
            "SELECT is_nullable FROM information_schema.columns"
                + " WHERE table_name = 'pypi_release' AND column_name = 'version'",
            String.class);
  }

  @Test
  @DisplayName("flips the column from nullable to NOT NULL")
  void flipsToNotNull() {
    this.migrateTo("44");
    assertThat(this.isNullable()).isEqualTo("YES");

    this.migrateTo("45");
    assertThat(this.isNullable()).isEqualTo("NO");
  }
}
