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
package io.repsy.os.server.protocols.cargo.shared.crate.entities;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.shared.entities.AbstractEntityIdentityTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CargoCrateMetaTest extends AbstractEntityIdentityTest<CargoCrateMeta> {

  @Override
  protected CargoCrateMeta newEntity(final UUID id) {
    final var cargoCrateMeta = new CargoCrateMeta();
    cargoCrateMeta.setId(id);
    cargoCrateMeta.setVersion("1.0.0");
    return cargoCrateMeta;
  }

  @Override
  protected void changeState(final CargoCrateMeta cargoCrateMeta) {
    cargoCrateMeta.setVersion("2.0.0");
    cargoCrateMeta.setDownloads(42L);
  }

  @Override
  protected CargoCrateMeta newProxy(final UUID id) {
    return new CargoCrateMeta() {
      @Override
      public UUID getId() {
        return id;
      }
    };
  }

  @Override
  protected void assignId(final CargoCrateMeta cargoCrateMeta, final UUID id) {
    cargoCrateMeta.setId(id);
  }

  @Test
  @DisplayName("Hashing and printing a cargo crate meta never touch its lazy associations")
  void hashCodeAndToStringSkipAssociations() {
    final var cargoCrateMeta = this.newEntity(UUID.randomUUID());

    assertThat(cargoCrateMeta.hashCode()).isEqualTo(CargoCrateMeta.class.hashCode());
    assertThat(cargoCrateMeta.toString()).doesNotContain("crate=");
  }
}
