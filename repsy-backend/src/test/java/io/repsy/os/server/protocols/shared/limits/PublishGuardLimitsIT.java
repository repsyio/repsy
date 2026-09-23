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

import io.repsy.os.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps the limits the Helm, Cargo, Go (RPS-1072), npm (RPS-1136) and Docker (RPS-1139, RPS-1140)
 * publish paths hold pushed metadata to equal to the PostgreSQL columns Flyway creates. It only
 * reads {@code information_schema}, so it commits no rows.
 */
@DisplayName("Helm, Cargo, Go, npm and Docker publish limits against the PostgreSQL schema")
class PublishGuardLimitsIT extends AbstractIntegrationTest {

  @Test
  @DisplayName("every limit equals its column, or is within an unbounded or longer one")
  void limitsMatchTheSchema() {
    assertThat(PublishGuardLimits.problems(this.jdbcTemplate, false))
        .as("limits that differ from the PostgreSQL schema")
        .isEmpty();
  }

  @Test
  @DisplayName("the check covers every column it names")
  void checkIsNotEmpty() {
    assertThat(PublishGuardLimits.limits()).hasSizeGreaterThan(30);
  }
}
