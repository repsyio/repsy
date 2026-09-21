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
package io.repsy.os.server.protocols.shared.limits;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.H2IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The H2 counterpart of {@link PublishGuardLimitsIT}: the limits must equal the columns the H2
 * Flyway scripts create, so a value the code lets through is never refused by H2. Cargo's {@code
 * links}, author and category are {@code varchar(255)} here and {@code text} in PostgreSQL
 * (RPS-1072).
 */
@DisplayName("Helm, Cargo and Go publish limits against the H2 schema (RPS-1072)")
class H2PublishGuardLimitsIT extends H2IntegrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("every limit equals its column, or is within a longer one")
  void limitsMatchTheSchema() {
    assertThat(PublishGuardLimits.problems(this.jdbcTemplate, true))
        .as("limits that differ from the H2 schema")
        .isEmpty();
  }
}
