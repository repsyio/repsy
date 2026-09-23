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

class CargoCategoryTest extends AbstractEntityIdentityTest<CargoCategory> {

  @Override
  protected CargoCategory newEntity(final UUID id) {
    final var cargoCategory = new CargoCategory();
    cargoCategory.setId(id);
    cargoCategory.setCategory("parsing");
    return cargoCategory;
  }

  @Override
  protected void changeState(final CargoCategory cargoCategory) {
    cargoCategory.setCategory("encoding");
  }

  @Override
  protected CargoCategory newProxy(final UUID id) {
    return new CargoCategory() {
      @Override
      public UUID getId() {
        return id;
      }
    };
  }

  @Override
  protected void assignId(final CargoCategory cargoCategory, final UUID id) {
    cargoCategory.setId(id);
  }

  @Test
  @DisplayName("Hashing and printing a cargo category never touch its lazy associations")
  void hashCodeAndToStringSkipAssociations() {
    final var cargoCategory = this.newEntity(UUID.randomUUID());
    cargoCategory.setCrates(new UntouchableSet<>());

    assertThat(cargoCategory.hashCode()).isEqualTo(CargoCategory.class.hashCode());
    assertThat(cargoCategory.toString()).doesNotContain("crates=");
  }
}
