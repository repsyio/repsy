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

class CargoCrateIndexTest extends AbstractEntityIdentityTest<CargoCrateIndex> {

  @Override
  protected CargoCrateIndex newEntity(final UUID id) {
    final var cargoCrateIndex = new CargoCrateIndex();
    cargoCrateIndex.setId(id);
    cargoCrateIndex.setName("serde");
    cargoCrateIndex.setVers("1.0.0");
    return cargoCrateIndex;
  }

  @Override
  protected void changeState(final CargoCrateIndex cargoCrateIndex) {
    cargoCrateIndex.setVers("2.0.0");
    cargoCrateIndex.setYanked(true);
  }

  @Override
  protected CargoCrateIndex newProxy(final UUID id) {
    return new CargoCrateIndex() {
      @Override
      public UUID getId() {
        return id;
      }
    };
  }

  @Override
  protected void assignId(final CargoCrateIndex cargoCrateIndex, final UUID id) {
    cargoCrateIndex.setId(id);
  }

  @Test
  @DisplayName("Hashing and printing a cargo crate index never touch its lazy associations")
  void hashCodeAndToStringSkipAssociations() {
    final var cargoCrateIndex = this.newEntity(UUID.randomUUID());

    assertThat(cargoCrateIndex.hashCode()).isEqualTo(CargoCrateIndex.class.hashCode());
    assertThat(cargoCrateIndex.toString()).doesNotContain("crate=");
  }
}
