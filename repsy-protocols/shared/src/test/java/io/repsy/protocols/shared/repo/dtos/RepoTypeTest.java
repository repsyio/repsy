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
package io.repsy.protocols.shared.repo.dtos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("RepoType")
class RepoTypeTest {

  @ParameterizedTest
  @EnumSource(RepoType.class)
  @DisplayName("fromString finds every type in upper, lower and mixed case")
  void fromStringIgnoresCase(final RepoType type) {
    final var name = type.name();
    final var mixed = name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);

    assertThat(RepoType.fromString(name)).contains(type);
    assertThat(RepoType.fromString(name.toLowerCase(Locale.ROOT))).contains(type);
    assertThat(RepoType.fromString(mixed)).contains(type);
    assertThat(RepoType.fromString("  " + name + " ")).contains(type);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "mvn", "go", "maven,npm", "MAVENS", "1"})
  @NullSource
  @DisplayName("fromString is empty for a value that names no type")
  void fromStringUnknown(final String value) {
    assertThat(RepoType.fromString(value)).isEmpty();
  }

  @Test
  @DisplayName("the JSON reader accepts either case and rejects an unknown value")
  void fromJson() {
    assertThat(RepoType.fromJson("maven")).isSameAs(RepoType.MAVEN);
    assertThat(RepoType.fromJson("GOLANG")).isSameAs(RepoType.GOLANG);
    assertThatThrownBy(() -> RepoType.fromJson("mvn"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mvn");
    assertThatThrownBy(() -> RepoType.fromJson(null)).isInstanceOf(IllegalArgumentException.class);
  }
}
