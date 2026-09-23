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
package io.repsy.os;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.EntityColumnSchemaChecks.Dialect;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The H2 counterpart of {@link EntitySchemaAnnotationIT}: the annotated lengths must fit the
 * columns the H2 Flyway scripts create, so a value the code lets through is never cut by H2
 * (RPS-1133).
 */
@DisplayName("Entity @Column mappings against the H2 schema")
class H2EntitySchemaAnnotationIT extends H2IntegrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private EntityManagerFactory entityManagerFactory;

  @Test
  @DisplayName("every entity fits its table")
  void everyEntityFitsSchema() {
    assertThat(
            EntityColumnSchemaChecks.problems(
                this.jdbcTemplate, this.entityManagerFactory, Dialect.H2))
        .as("entity mappings that do not fit the H2 schema")
        .isEmpty();
  }

  @Test
  @DisplayName("a divergent mapping is reported")
  void divergentMappingIsReported() {
    assertThat(
            EntityColumnSchemaChecks.problems(
                this.jdbcTemplate, DivergentRepoMapping.class, Dialect.H2))
        .hasSize(5);
  }
}
