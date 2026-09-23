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
package io.repsy.os.server.protocols.cargo.shared.crate.mappers;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.server.protocols.cargo.shared.crate.entities.CargoCrate;
import io.repsy.os.server.protocols.cargo.shared.crate.entities.CargoCrateIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1212: the served sparse-index entry must carry the crate's original, as-published spelling
 * (e.g. {@code my-crate}), never the normalized lookup key used internally (e.g. {@code my_crate}).
 * Real {@code cargo} clients cross-check the entry's {@code name} against the dependency name they
 * asked for and refuse a mismatch.
 */
@DisplayName("CargoCrateConverter.toCrateIndexEntry")
class CargoCrateConverterTest {

  private final CargoCrateConverter converter = newConverter();

  private static CargoCrateConverter newConverter() {
    final var impl = new CargoCrateConverterImpl();
    ReflectionTestUtils.setField(
        impl, "cargoJsonConverter", new CargoJsonConverter(new ObjectMapper()));
    return impl;
  }

  private static CargoCrateIndex index(final String name, final String originalName) {
    final var crate = new CargoCrate();
    crate.setName(name);
    crate.setOriginalName(originalName);

    final var index = new CargoCrateIndex();
    index.setCrate(crate);
    index.setName(name);
    index.setVers("1.0.0");
    index.setCksum("abc123");
    index.setYanked(true);
    index.setLinks("some-links");
    index.setV(2);
    index.setRustVersion("1.75");

    return index;
  }

  @Test
  @DisplayName("reports the crate's original, as-published spelling, not the normalized lookup key")
  void reportsOriginalNameNotNormalized() {
    final var entry = this.converter.toCrateIndexEntry(index("my_crate", "my-crate"));

    assertThat(entry.name()).isEqualTo("my-crate");
    assertThat(entry.name()).isNotEqualTo("my_crate");
  }

  @Test
  @DisplayName("is unchanged for a crate whose name never needed normalizing")
  void allUnderscoreCrateIsUnaffected() {
    final var entry = this.converter.toCrateIndexEntry(index("my_crate", "my_crate"));

    assertThat(entry.name()).isEqualTo("my_crate");
  }

  @Test
  @DisplayName("still maps the other index fields")
  void mapsOtherFields() {
    final var entry = this.converter.toCrateIndexEntry(index("my_crate", "my-crate"));

    assertThat(entry.vers()).isEqualTo("1.0.0");
    assertThat(entry.cksum()).isEqualTo("abc123");
    assertThat(entry.yanked()).isTrue();
    assertThat(entry.links()).isEqualTo("some-links");
    assertThat(entry.v()).isEqualTo(2);
    assertThat(entry.rustVersion()).isEqualTo("1.75");
  }
}
