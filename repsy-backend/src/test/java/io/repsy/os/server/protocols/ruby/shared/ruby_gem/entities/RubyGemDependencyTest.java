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

import io.repsy.os.shared.entities.AbstractEntityIdentityTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RubyGemDependencyTest extends AbstractEntityIdentityTest<RubyGemDependency> {

  @Override
  protected RubyGemDependency newEntity(final UUID id) {
    final var rubyGemDependency = new RubyGemDependency();
    rubyGemDependency.setId(id);
    rubyGemDependency.setName("rack");
    return rubyGemDependency;
  }

  @Override
  protected void changeState(final RubyGemDependency rubyGemDependency) {
    rubyGemDependency.setName("rake");
    rubyGemDependency.setRequirements(">= 1");
  }

  @Override
  protected RubyGemDependency newProxy(final UUID id) {
    return new RubyGemDependency() {
      @Override
      public UUID getId() {
        return id;
      }
    };
  }

  @Override
  protected void assignId(final RubyGemDependency rubyGemDependency, final UUID id) {
    rubyGemDependency.setId(id);
  }

  @Test
  @DisplayName("Hashing and printing a ruby gem dependency never touch its lazy associations")
  void hashCodeAndToStringSkipAssociations() {
    final var rubyGemDependency = this.newEntity(UUID.randomUUID());

    assertThat(rubyGemDependency.hashCode()).isEqualTo(RubyGemDependency.class.hashCode());
    assertThat(rubyGemDependency.toString()).doesNotContain("gemVersion=");
  }
}
