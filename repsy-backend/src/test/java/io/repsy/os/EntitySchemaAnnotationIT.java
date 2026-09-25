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

/**
 * Keeps the {@code @Column} mapping of every JPA entity equal to the PostgreSQL schema Flyway
 * creates. Hibernate does not validate it ({@code ddl-auto: none}), so a stale length would let a
 * value through that the database refuses (RPS-1133, after RPS-1069 did the same for NuGet). It
 * only reads {@code information_schema}, so it commits no rows.
 */
@DisplayName("Entity @Column mappings against the PostgreSQL schema")
class EntitySchemaAnnotationIT extends AbstractIntegrationTest {

  @Autowired private EntityManagerFactory entityManagerFactory;

  @Test
  @DisplayName("every entity is checked")
  void everyEntityIsChecked() {
    assertThat(EntityColumnSchemaChecks.entities(this.entityManagerFactory))
        .as("a metamodel that lists almost no entity would make the check below pass vacuously")
        .hasSizeGreaterThan(40);
  }

  @Test
  @DisplayName("every entity matches its table")
  void everyEntityMatchesSchema() {
    assertThat(
            EntityColumnSchemaChecks.problems(
                this.jdbcTemplate, this.entityManagerFactory, Dialect.POSTGRESQL))
        .as("entity mappings that differ from the PostgreSQL schema")
        .isEmpty();
  }

  @Test
  @DisplayName("a divergent mapping is reported")
  void divergentMappingIsReported() {
    assertThat(
            EntityColumnSchemaChecks.problems(
                this.jdbcTemplate, DivergentRepoMapping.class, Dialect.POSTGRESQL))
        .hasSize(5)
        .anyMatch(p -> p.contains("name") && p.contains("varchar(25)") && p.contains("length=26"))
        .anyMatch(p -> p.contains("description") && p.contains("allows null"))
        .anyMatch(p -> p.contains("security_scan_enabled") && p.contains("NOT NULL"))
        .anyMatch(p -> p.contains("type") && p.contains("columnDefinition"))
        .anyMatch(p -> p.contains("no_such_column") && p.contains("no such column"));
  }
}
