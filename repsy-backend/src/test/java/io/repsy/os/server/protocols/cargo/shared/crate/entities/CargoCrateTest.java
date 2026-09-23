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
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CargoCrateTest extends AbstractEntityIdentityTest<CargoCrate> {

  @Override
  protected CargoCrate newEntity(final UUID id) {
    final var cargoCrate = new CargoCrate();
    cargoCrate.setId(id);
    cargoCrate.setName("serde");
    return cargoCrate;
  }

  @Override
  protected void changeState(final CargoCrate cargoCrate) {
    cargoCrate.setName("serde2");
    cargoCrate.setMaxVersion("2.0.0");
    cargoCrate.setTotalDownloads(42L);
    cargoCrate.setLastUpdatedAt(Instant.now());
  }

  @Override
  protected CargoCrate newProxy(final UUID id) {
    return new CargoCrate() {
      @Override
      public UUID getId() {
        return id;
      }
    };
  }

  @Override
  protected void assignId(final CargoCrate cargoCrate, final UUID id) {
    cargoCrate.setId(id);
  }

  @Test
  @DisplayName("Hashing and printing a cargo crate never touch its lazy associations")
  void hashCodeAndToStringSkipAssociations() {
    final var cargoCrate = this.newEntity(UUID.randomUUID());
    cargoCrate.setCrateIndexes(new UntouchableSet<>());
    cargoCrate.setCrateMetas(new UntouchableSet<>());
    cargoCrate.setAuthors(new UntouchableSet<>());
    cargoCrate.setKeywords(new UntouchableSet<>());
    cargoCrate.setCategories(new UntouchableSet<>());

    assertThat(cargoCrate.hashCode()).isEqualTo(CargoCrate.class.hashCode());
    assertThat(cargoCrate.toString())
        .doesNotContain(
            "repo=", "crateIndexes=", "crateMetas=", "authors=", "keywords=", "categories=");
  }
}
