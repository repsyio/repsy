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

import io.repsy.os.H2IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The H2 counterpart of {@link RubySchemaAnnotationIT}: the annotated lengths must equal the
 * columns the H2 Flyway scripts create, so a value the code lets through is never refused by H2
 * (RPS-1071).
 */
@DisplayName("Ruby entity @Column annotations against the H2 schema")
class H2RubySchemaAnnotationIT extends H2IntegrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("RubyGem fits ruby_gem")
  void rubyGemFitsSchema() {
    assertThat(RubyColumnSchemaChecks.problems(this.jdbcTemplate, RubyGem.class))
        .as("RubyGem annotations that do not fit the H2 schema")
        .isEmpty();
  }

  @Test
  @DisplayName("RubyGemVersion fits ruby_gem_version")
  void rubyGemVersionFitsSchema() {
    assertThat(RubyColumnSchemaChecks.problems(this.jdbcTemplate, RubyGemVersion.class))
        .as("RubyGemVersion annotations that do not fit the H2 schema")
        .isEmpty();
  }

  @Test
  @DisplayName("RubyGemDependency fits ruby_gem_dependency")
  void rubyGemDependencyFitsSchema() {
    assertThat(RubyColumnSchemaChecks.problems(this.jdbcTemplate, RubyGemDependency.class))
        .as("RubyGemDependency annotations that do not fit the H2 schema")
        .isEmpty();
  }
}
