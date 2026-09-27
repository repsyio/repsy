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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Puts the rows of a PostgreSQL table in a physical order that a query without an {@code ORDER BY}
 * would answer in, so a test can prove that a query orders its rows itself (RPS-1614).
 *
 * <p>PostgreSQL answers an unordered sequential scan in heap order, and an {@code UPDATE} writes a
 * new version of the row at the end of the heap. Rows that are inserted one after the other are
 * therefore read back in insertion order, which is also the order of their ids (UUIDv7) and of
 * their timestamps, so a test on such rows passes without an {@code ORDER BY}. This rewrites the
 * rows in the order given, so the heap order is that one, and checks it did.
 *
 * <p>The planner may read a small table through an index instead, and an index answers in the order
 * the rows were first inserted in (the updates are HOT, so the index entries stay), so this also
 * turns index and bitmap scans off for the rest of the transaction of the test. It therefore needs
 * a test that runs in a transaction, as most of them do.
 */
public final class HeapOrder {

  private static final int ATTEMPTS = 10;

  private HeapOrder() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Rewrites the rows of {@code table} with the given ids one by one, in the order given, until a
   * scan in physical order returns them in that order.
   *
   * @param table the unquoted table name
   * @param physicalOrder the ids in the order the heap must hold their rows
   * @throws AssertionError if the heap does not take that order, so a test does not pass for a
   *     reason that has nothing to do with the query
   */
  public static void rewriteInOrder(
      final JdbcTemplate jdbcTemplate, final String table, final List<UUID> physicalOrder) {
    jdbcTemplate.execute("set local enable_indexscan = off");
    jdbcTemplate.execute("set local enable_indexonlyscan = off");
    jdbcTemplate.execute("set local enable_bitmapscan = off");

    final var marks = String.join(", ", Collections.nCopies(physicalOrder.size(), "?"));

    for (var attempt = 0; attempt < ATTEMPTS; attempt++) {
      for (final var id : physicalOrder) {
        jdbcTemplate.update(
            "update \"%s\" set \"id\" = \"id\" where \"id\" = ?".formatted(table), id);
      }

      final List<UUID> actual =
          new ArrayList<>(
              jdbcTemplate.queryForList(
                  "select \"id\" from \"%s\" where \"id\" in (%s) order by ctid"
                      .formatted(table, marks),
                  UUID.class,
                  physicalOrder.toArray()));

      if (actual.equals(physicalOrder)) {
        return;
      }
    }

    throw new AssertionError(
        "The heap of " + table + " did not take the order " + physicalOrder + " after rewrites");
  }
}
