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
package io.repsy.os.shared.repo.dtos;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RepoInfoTest {

  private static final UUID STORAGE_KEY = UUID.fromString("00000000-0000-0000-0000-000000000001");

  private static RepoInfo.RepoInfoBuilder<?, ?> builder() {

    return RepoInfo.builder()
        .id(UUID.fromString("00000000-0000-0000-0000-000000000002"))
        .storageKey(STORAGE_KEY)
        .name("maven-repo")
        .diskUsage(1L)
        .type(RepoType.MAVEN);
  }

  @Test
  void equalsAndHashCodeShouldMatchWhenAllInheritedFieldsMatch() {

    final var first = builder().build();
    final var second = builder().build();

    assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
  }

  @Test
  void equalsShouldDifferWhenAnInheritedFieldDiffers() {

    final var first = builder().build();
    final var renamed = builder().name("other-repo").build();
    final var otherType = builder().type(RepoType.NPM).build();
    final var otherStorage = builder().storageKey(UUID.randomUUID()).build();

    assertThat(first).isNotEqualTo(renamed).isNotEqualTo(otherType).isNotEqualTo(otherStorage);
  }

  @Test
  void hashCodeShouldDifferWhenAnInheritedFieldDiffers() {

    final var first = builder().build();
    final var renamed = builder().name("other-repo").build();

    assertThat(first.hashCode()).isNotEqualTo(renamed.hashCode());
  }
}
