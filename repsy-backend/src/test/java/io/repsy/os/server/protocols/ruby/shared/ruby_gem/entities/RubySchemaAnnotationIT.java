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
package io.repsy.os.server.protocols.ruby.shared.ruby_gem.entities;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps the {@code @Column} annotations of the Ruby entities equal to the PostgreSQL schema Flyway
 * creates. Hibernate does not validate them, and the lengths are the limits a pushed gem is held
 * to, so a stale one would let a value through that the database refuses (RPS-1071). It only reads
 * {@code information_schema}, so it commits no rows.
 */
@DisplayName("Ruby entity @Column annotations against the PostgreSQL schema")
class RubySchemaAnnotationIT extends AbstractIntegrationTest {

  @Test
  @DisplayName("RubyGem matches ruby_gem")
  void rubyGemMatchesSchema() {
    assertThat(RubyColumnSchemaChecks.problems(this.jdbcTemplate, RubyGem.class))
        .as("RubyGem annotations that differ from the PostgreSQL schema")
        .isEmpty();
  }

  @Test
  @DisplayName("RubyGemVersion matches ruby_gem_version")
  void rubyGemVersionMatchesSchema() {
    assertThat(RubyColumnSchemaChecks.problems(this.jdbcTemplate, RubyGemVersion.class))
        .as("RubyGemVersion annotations that differ from the PostgreSQL schema")
        .isEmpty();
  }

  @Test
  @DisplayName("RubyGemDependency matches ruby_gem_dependency")
  void rubyGemDependencyMatchesSchema() {
    assertThat(RubyColumnSchemaChecks.problems(this.jdbcTemplate, RubyGemDependency.class))
        .as("RubyGemDependency annotations that differ from the PostgreSQL schema")
        .isEmpty();
  }
}
