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
package io.repsy.os.server.protocols.shared;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.Future;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Lets a race test wait for the state it needs instead of sleeping for a guessed time: the second
 * of two operations is blocked on a lock the first one holds, or it has finished without ever
 * needing one.
 */
public final class PostgresLockWaits {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(10);

  private PostgresLockWaits() {}

  /**
   * Waits until a database session is blocked on a lock, or the given operation has finished (as it
   * would without the lock). Either way the race is set up: the operation that is being held has
   * nothing left to overtake.
   */
  public static void awaitBlockedOnALockOrDone(
      final JdbcTemplate jdbcTemplate, final Future<?> operation) {
    await("the operation blocked or finished")
        .atMost(TIMEOUT)
        .pollInterval(POLL_INTERVAL)
        .until(() -> operation.isDone() || someSessionWaitsForALock(jdbcTemplate));
  }

  private static boolean someSessionWaitsForALock(final JdbcTemplate jdbcTemplate) {
    final var waiting =
        jdbcTemplate.queryForObject(
            "select count(*) from pg_stat_activity"
                + " where datname = current_database() and wait_event_type = 'Lock'",
            Long.class);

    return waiting != null && waiting > 0;
  }
}
