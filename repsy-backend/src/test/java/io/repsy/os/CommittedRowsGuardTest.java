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
package io.repsy.os;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.CommittedRowsGuard.RowChange;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CommittedRowsGuard: comparing snapshots and reporting the difference")
class CommittedRowsGuardTest {

  @Test
  @DisplayName("identical counts are not a difference")
  void identicalCountsAreNotADifference() {
    final var counts = Map.of("users", 1L, "repo", 9L);

    assertThat(CommittedRowsGuard.changes(counts, Map.of("repo", 9L, "users", 1L))).isEmpty();
  }

  @Test
  @DisplayName("added and deleted rows are reported per table, sorted by name")
  void reportsAddedAndDeletedRowsSortedByName() {
    final var before = Map.of("users", 1L, "repo", 9L, "vulnerability_scan", 4L, "npm_package", 0L);
    final var after = Map.of("users", 3L, "repo", 9L, "vulnerability_scan", 2L, "npm_package", 0L);

    assertThat(CommittedRowsGuard.changes(before, after))
        .containsExactly(new RowChange("users", 1, 3), new RowChange("vulnerability_scan", 4, 2));
  }

  @Test
  @DisplayName("a table missing from one snapshot counts as empty")
  void aMissingTableCountsAsEmpty() {
    assertThat(CommittedRowsGuard.changes(Map.of(), Map.of("refresh_tokens", 2L)))
        .containsExactly(new RowChange("refresh_tokens", 0, 2));
    assertThat(CommittedRowsGuard.changes(Map.of("refresh_tokens", 0L), Map.of())).isEmpty();
  }

  @Test
  @DisplayName("a row change prints the counts and the signed delta")
  void aRowChangePrintsCountsAndDelta() {
    assertThat(new RowChange("users", 1, 3)).hasToString("users: 1 -> 3 (+2)");
    assertThat(new RowChange("repo", 9, 7)).hasToString("repo: 9 -> 7 (-2)");
  }

  @Test
  @DisplayName("the message names the class and every leaked table")
  void theMessageNamesTheClassAndTables() {
    final var message =
        CommittedRowsGuard.message(
            "io.repsy.os.SomeIT",
            List.of(new RowChange("repo", 9, 11), new RowChange("users", 1, 2)),
            List.of());

    assertThat(message)
        .contains("io.repsy.os.SomeIT")
        .contains("repo: 9 -> 11 (+2)")
        .contains("users: 1 -> 2 (+1)")
        .contains("later classes are not affected")
        .doesNotContain("could not restore");
  }

  @Test
  @DisplayName("the message lists what the cleanup could not restore")
  void theMessageListsWhatCouldNotBeRestored() {
    final var message =
        CommittedRowsGuard.message(
            "io.repsy.os.SomeIT",
            List.of(new RowChange("refresh_tokens", 0, 1)),
            List.of(new RowChange("refresh_tokens", 0, 1)));

    assertThat(message)
        .contains("could not restore")
        .contains("refresh_tokens: 0 -> 1 (+1)")
        .doesNotContain("later classes are not affected");
  }
}
