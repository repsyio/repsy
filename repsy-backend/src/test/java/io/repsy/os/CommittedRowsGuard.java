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

import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.platform.commons.support.AnnotationSupport;
import org.opentest4j.AssertionFailedError;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Fails an integration test class that leaves committed rows behind in the database every {@link
 * AbstractIntegrationTest} subclass shares (RPS-1011).
 *
 * <p>Most tests run in a transaction that is rolled back, but the ones that need committed data (an
 * {@code @Async} listener cannot see an open transaction) delete only what they create. A class
 * that forgets a cleanup, or fails before its {@code @AfterEach}, would otherwise break an
 * unrelated class that happens to run later, and the failure would point at the victim. This
 * extension moves the failure to the class that caused it:
 *
 * <ol>
 *   <li>before a class it boots the application context, waits until the startup seeding has
 *       committed its default repos, and snapshots the row count of every table (Flyway's history
 *       excluded) plus the ids of the {@code users} and {@code repo} rows;
 *   <li>after the class (so after its own {@code @AfterEach} and {@code @AfterAll} methods) it
 *       compares the counts. Rows that an {@code @Async} task is still writing get a few seconds to
 *       settle first;
 *   <li>on a difference it deletes the {@code users} and {@code repo} rows the class added, which
 *       takes their child rows along (every foreign key cascades), so the next class starts from
 *       the state this one found, and then fails the class with the tables and counts that
 *       differed.
 * </ol>
 *
 * <p>The snapshot is taken per class rather than once per JVM, so a leak is reported once, against
 * the class that left it, and not again against every class that follows.
 *
 * <p>It is registered on {@link AbstractIntegrationTest}. Classes that own their database (the H2
 * suites, {@code DefaultRepoSeedingIT}) don't extend it and aren't checked. A {@code @Nested} class
 * is checked as part of the class that encloses it, not on its own.
 */
public final class CommittedRowsGuard implements BeforeAllCallback, AfterAllCallback {

  private static final Namespace NAMESPACE = Namespace.create(CommittedRowsGuard.class);
  private static final String BASELINE_KEY = "baseline";

  private static final Duration SEEDING_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(5);
  private static final long POLL_MILLIS = 50;

  /**
   * One row per table with its row count. {@code query_to_xml} is what lets a single static
   * statement count tables whose names are only known at run time.
   */
  private static final String ROW_COUNTS_SQL =
      """
      select table_name,
        (xpath('/row/c/text()',
          query_to_xml(
            format('select count(*) as c from %I.%I', table_schema, table_name),
            false, true, '')))[1]::text::bigint as row_count
      from information_schema.tables
      where table_schema = current_schema()
        and table_type = 'BASE TABLE'
        and table_name <> 'flyway_schema_history'
      order by table_name
      """;

  private static final String REPO_COUNT_SQL = "select count(*) from repo";
  private static final String REPO_IDS_SQL = "select id from repo";
  private static final String USER_IDS_SQL = "select id from users";
  private static final String DELETE_REPOS_SQL = "delete from repo where id in (:ids)";
  private static final String DELETE_USERS_SQL = "delete from users where id in (:ids)";

  /** The state of the database at one point in time. */
  record Snapshot(Map<String, Long> rowCounts, Set<UUID> repoIds, Set<UUID> userIds) {}

  /** A table whose row count differs between two snapshots. */
  record RowChange(String table, long before, long after) {

    @Override
    public String toString() {
      final var delta = this.after - this.before;

      return "%s: %d -> %d (%+d)".formatted(this.table, this.before, this.after, delta);
    }
  }

  @Override
  public void beforeAll(final ExtensionContext context) throws InterruptedException {
    if (isNested(context)) {
      return;
    }

    final var jdbc = jdbcTemplate(context);

    awaitDefaultRepos(jdbc);
    context.getStore(NAMESPACE).put(BASELINE_KEY, snapshot(jdbc));
  }

  @Override
  public void afterAll(final ExtensionContext context) throws InterruptedException {
    if (isNested(context)) {
      return;
    }

    final var baseline = context.getStore(NAMESPACE).remove(BASELINE_KEY, Snapshot.class);

    if (baseline == null) {
      return;
    }

    final var jdbc = jdbcTemplate(context);
    final var current = awaitSettled(jdbc, baseline);
    final var leaked = changes(baseline.rowCounts(), current.rowCounts());

    if (leaked.isEmpty()) {
      return;
    }

    removeAddedRows(jdbc, baseline, current);

    final var remaining = changes(baseline.rowCounts(), snapshot(jdbc).rowCounts());

    throw new AssertionFailedError(
        message(context.getRequiredTestClass().getName(), leaked, remaining));
  }

  /**
   * Blocks until the application's asynchronous startup seeding has committed its default repos.
   *
   * <p>{@code AdminUserInitializer} publishes a {@code UserCreatedEvent} and the {@code @Async}
   * per-protocol {@code *AuthListener}s then create one default repo per {@link RepoType} (named
   * {@code maven}, {@code npm}, ..., {@code go}, ...) in their own committed transactions. Without
   * this barrier the first class of a JVM would race that seeding, and anything that counts or
   * lists repos would see a different number depending on timing.
   */
  private static void awaitDefaultRepos(final JdbcTemplate jdbc) throws InterruptedException {
    final var deadline = System.nanoTime() + SEEDING_TIMEOUT.toNanos();

    while (count(jdbc, REPO_COUNT_SQL) < RepoType.values().length) {
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException("Default repos were not seeded within " + SEEDING_TIMEOUT);
      }

      Thread.sleep(POLL_MILLIS);
    }
  }

  /**
   * Re-reads the database until it matches the baseline or {@link #SETTLE_TIMEOUT} is over. A class
   * that ends while an {@code @Async} task is still committing would otherwise be blamed for a row
   * that is about to be removed again; a real leak only costs the wait.
   */
  private static Snapshot awaitSettled(final JdbcTemplate jdbc, final Snapshot baseline)
      throws InterruptedException {
    final var deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
    var current = snapshot(jdbc);

    while (!changes(baseline.rowCounts(), current.rowCounts()).isEmpty()
        && System.nanoTime() < deadline) {
      Thread.sleep(POLL_MILLIS);
      current = snapshot(jdbc);
    }

    return current;
  }

  /**
   * A {@code @Nested} class is part of the class that encloses it, whose {@code @AfterAll} may be
   * what cleans up after all of them, so only the outermost class is checked.
   */
  private static boolean isNested(final ExtensionContext context) {
    return AnnotationSupport.isAnnotated(context.getRequiredTestClass(), Nested.class);
  }

  private static JdbcTemplate jdbcTemplate(final ExtensionContext context) {
    final var dataSource = SpringExtension.getApplicationContext(context).getBean(DataSource.class);

    return new JdbcTemplate(dataSource);
  }

  private static Snapshot snapshot(final JdbcTemplate jdbc) {
    final Map<String, Long> rowCounts = new LinkedHashMap<>();

    jdbc.query(
        ROW_COUNTS_SQL,
        rs -> {
          rowCounts.put(rs.getString("table_name"), rs.getLong("row_count"));
        });

    return new Snapshot(
        rowCounts,
        new HashSet<>(jdbc.queryForList(REPO_IDS_SQL, UUID.class)),
        new HashSet<>(jdbc.queryForList(USER_IDS_SQL, UUID.class)));
  }

  private static long count(final JdbcTemplate jdbc, final String sql) {
    final var count = jdbc.queryForObject(sql, Long.class);

    return count == null ? 0 : count;
  }

  /**
   * Deletes the {@code repo} and {@code users} rows that exist now but not in the baseline. Child
   * rows (versions, scans, findings, ...) follow through their {@code ON DELETE CASCADE} keys.
   */
  private static void removeAddedRows(
      final JdbcTemplate jdbc, final Snapshot baseline, final Snapshot current) {
    final var named = new NamedParameterJdbcTemplate(jdbc);

    deleteIds(named, DELETE_REPOS_SQL, added(baseline.repoIds(), current.repoIds()));
    deleteIds(named, DELETE_USERS_SQL, added(baseline.userIds(), current.userIds()));
  }

  private static void deleteIds(
      final NamedParameterJdbcTemplate named, final String sql, final List<UUID> ids) {
    if (!ids.isEmpty()) {
      named.update(sql, Map.of("ids", ids));
    }
  }

  private static List<UUID> added(final Set<UUID> before, final Set<UUID> after) {
    final var added = new ArrayList<>(after);
    added.removeAll(before);

    return added;
  }

  /** The tables whose row count differs between the two snapshots, sorted by table name. */
  static List<RowChange> changes(final Map<String, Long> before, final Map<String, Long> after) {
    final var tables = new TreeSet<>(before.keySet());
    tables.addAll(after.keySet());

    final List<RowChange> changes = new ArrayList<>();

    for (final var table : tables) {
      final var rowsBefore = before.getOrDefault(table, 0L);
      final var rowsAfter = after.getOrDefault(table, 0L);

      if (rowsBefore != rowsAfter) {
        changes.add(new RowChange(table, rowsBefore, rowsAfter));
      }
    }

    return changes;
  }

  /**
   * The failure text: which class, which tables (and how the counts moved), and what state the
   * database is in afterwards. {@code remaining} is what the cleanup could not bring back: rows in
   * tables other than {@code users} and {@code repo}, or rows the class deleted.
   */
  static String message(
      final String testClass, final List<RowChange> leaked, final List<RowChange> remaining) {
    final var text = new StringBuilder();

    text.append(testClass)
        .append(" left the shared test database different from how it found it (RPS-1011):");

    for (final var change : leaked) {
      text.append("\n  ").append(change);
    }

    if (remaining.isEmpty()) {
      text.append(
          "\nThe users and repos it added were removed, so later classes are not affected.");
    } else {
      text.append("\nThe guard could not restore these tables, later classes may be affected:");

      for (final var change : remaining) {
        text.append("\n  ").append(change);
      }
    }

    text.append(
        "\nDelete what a test commits (an @AfterEach that removes the rows it created), and don't"
            + " delete rows that were there before the class started.");

    return text.toString();
  }
}
