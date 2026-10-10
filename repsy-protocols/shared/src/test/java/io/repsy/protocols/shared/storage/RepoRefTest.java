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
package io.repsy.protocols.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RepoRef")
class RepoRefTest {

  @Test
  @DisplayName("of() takes the storage key and the name of the repo info, not its row id")
  void ofTakesStorageKeyAndName() {
    final var rowId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    final var storageKey = UUID.fromString("00000000-0000-0000-0000-000000000002");
    final var info =
        BaseRepoInfo.<UUID>builder().id(rowId).storageKey(storageKey).name("repo").build();

    final var ref = RepoRef.of(info);

    assertThat(ref.id()).isEqualTo(storageKey);
    assertThat(ref.name()).isEqualTo("repo");
    assertThat(ref).isEqualTo(new RepoRef(storageKey, "repo"));
  }
}
