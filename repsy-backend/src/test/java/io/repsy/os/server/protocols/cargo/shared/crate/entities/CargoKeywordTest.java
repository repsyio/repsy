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

class CargoKeywordTest extends AbstractEntityIdentityTest<CargoKeyword> {

  @Override
  protected CargoKeyword newEntity(final UUID id) {
    final var cargoKeyword = new CargoKeyword();
    cargoKeyword.setId(id);
    cargoKeyword.setKeyword("json");
    return cargoKeyword;
  }

  @Override
  protected void changeState(final CargoKeyword cargoKeyword) {
    cargoKeyword.setKeyword("yaml");
  }

  @Override
  protected CargoKeyword newProxy(final UUID id) {
    return new CargoKeyword() {
      @Override
      public UUID getId() {
        return id;
      }
    };
  }

  @Override
  protected void assignId(final CargoKeyword cargoKeyword, final UUID id) {
    cargoKeyword.setId(id);
  }

  @Test
  @DisplayName("Hashing and printing a cargo keyword never touch its lazy associations")
  void hashCodeAndToStringSkipAssociations() {
    final var cargoKeyword = this.newEntity(UUID.randomUUID());
    cargoKeyword.setCrates(new UntouchableSet<>());

    assertThat(cargoKeyword.hashCode()).isEqualTo(CargoKeyword.class.hashCode());
    assertThat(cargoKeyword.toString()).doesNotContain("crates=");
  }
}
