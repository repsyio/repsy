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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * V0040 and V0041 (RPS-2117) are comment-only on H2, which has no trigram indexes: the history must
 * still apply cleanly, so both dialects keep one version sequence.
 */
@DisplayName("V0040 and V0041 apply as no-ops on H2")
class V0041TrigramSearchIndexesTest {

  @Test
  @DisplayName("the H2 history migrates through 41")
  void migrates() {
    final var dataSource =
        new SingleConnectionDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "sa",
            "",
            true);
    try {
      final var flyway =
          Flyway.configure()
              .dataSource(dataSource)
              .locations("classpath:db/migration/h2")
              .schemas("public")
              .defaultSchema("public")
              .target("41")
              .load();

      assertThat(flyway.migrate().success).isTrue();
      assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("0041");
    } finally {
      dataSource.destroy();
    }
  }
}
