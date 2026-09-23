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
package io.repsy.os.server.protocols.golang.shared.go_module.entities;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.shared.entities.AbstractEntityIdentityTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GoModuleVersionTest extends AbstractEntityIdentityTest<GoModuleVersion> {

  @Override
  protected GoModuleVersion newEntity(final UUID id) {
    final var goModuleVersion = new GoModuleVersion();
    goModuleVersion.setId(id);
    goModuleVersion.setVersion("v1.0.0");
    return goModuleVersion;
  }

  @Override
  protected void changeState(final GoModuleVersion goModuleVersion) {
    goModuleVersion.setVersion("v2.0.0");
    goModuleVersion.setCreatedAt(Instant.now());
  }

  @Override
  protected GoModuleVersion newProxy(final UUID id) {
    return new GoModuleVersion() {
      @Override
      public UUID getId() {
        return id;
      }
    };
  }

  @Override
  protected void assignId(final GoModuleVersion goModuleVersion, final UUID id) {
    goModuleVersion.setId(id);
  }

  @Test
  @DisplayName("Hashing and printing a go module version never touch its lazy associations")
  void hashCodeAndToStringSkipAssociations() {
    final var goModuleVersion = this.newEntity(UUID.randomUUID());

    assertThat(goModuleVersion.hashCode()).isEqualTo(GoModuleVersion.class.hashCode());
    assertThat(goModuleVersion.toString()).doesNotContain("goModule=");
  }
}
