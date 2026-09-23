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
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RubyGemVersionTest extends AbstractEntityIdentityTest<RubyGemVersion> {

  @Override
  protected RubyGemVersion newEntity(final UUID id) {
    final var rubyGemVersion = new RubyGemVersion();
    rubyGemVersion.setId(id);
    rubyGemVersion.setVersion("1.0.0");
    return rubyGemVersion;
  }

  @Override
  protected void changeState(final RubyGemVersion rubyGemVersion) {
    rubyGemVersion.setVersion("2.0.0");
    rubyGemVersion.setYanked(true);
    rubyGemVersion.setCreatedAt(Instant.now());
  }

  @Override
  protected RubyGemVersion newProxy(final UUID id) {
    return new RubyGemVersion() {
      @Override
      public UUID getId() {
        return id;
      }
    };
  }

  @Override
  protected void assignId(final RubyGemVersion rubyGemVersion, final UUID id) {
    rubyGemVersion.setId(id);
  }

  @Test
  @DisplayName("Hashing and printing a ruby gem version never touch its lazy associations")
  void hashCodeAndToStringSkipAssociations() {
    final var rubyGemVersion = this.newEntity(UUID.randomUUID());
    rubyGemVersion.setDependencies(new UntouchableSet<>());

    assertThat(rubyGemVersion.hashCode()).isEqualTo(RubyGemVersion.class.hashCode());
    assertThat(rubyGemVersion.toString()).doesNotContain("gem=", "dependencies=");
  }
}
