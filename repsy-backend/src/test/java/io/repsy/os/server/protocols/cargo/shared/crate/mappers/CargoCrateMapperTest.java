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
@DisplayName("CargoCrateMapper.toCrateIndexEntry")
class CargoCrateMapperTest {

  private final CargoCrateMapper converter = newConverter();

  private static CargoCrateMapper newConverter() {
    final var impl = new CargoCrateMapperImpl();
    ReflectionTestUtils.setField(impl, "cargoJsonMapper", new CargoJsonMapper(new ObjectMapper()));
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

  @Test
  @DisplayName("RPS-1730: features must be empty map when null, not null")
  void featuresIsEmptyMapWhenNull() {
    final var idx = index("my_crate", "my-crate");
    idx.setFeatures(null);
    final var entry = this.converter.toCrateIndexEntry(idx);

    assertThat(entry.features()).isNotNull().isEmpty();
  }

  @Test
  @DisplayName("RPS-1730: features2 must be null when no v2 data, to be omitted from JSON")
  void features2IsNullWhenNoV2Data() {
    final var idx = index("my_crate", "my-crate");
    idx.setFeatures2(null);
    idx.setV(1);
    final var entry = this.converter.toCrateIndexEntry(idx);

    assertThat(entry.features2()).isNull();
  }

  @Test
  @DisplayName("RPS-1730: features2 absent from JSON when null due to JsonInclude.NON_NULL")
  void features2OmittedFromJsonWhenNull() throws Exception {
    final var idx = index("my_crate", "my-crate");
    idx.setFeatures(null);
    idx.setFeatures2(null);
    idx.setV(1);
    final var entry = this.converter.toCrateIndexEntry(idx);

    final var objectMapper = new ObjectMapper();
    final var json = objectMapper.writeValueAsString(entry);

    assertThat(json).doesNotContain("features2");
    assertThat(json).contains("\"features\":{}");
  }
}
