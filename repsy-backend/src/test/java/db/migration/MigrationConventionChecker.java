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
package db.migration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Static checks of the safe migration convention (RPS-2119, see "Database" in AGENTS.md). It reads
 * the migration scripts only: no database, no Flyway, a few milliseconds.
 *
 * <p>A table that the same file creates is empty, so the rules about locks and long statements do
 * not apply to it. A deliberate exception in a new file is a line {@code -- migration-check: allow
 * <rule> <reason>} in the script itself (the reason is required, so it is reviewed with the file).
 * Existing violations are pinned by name in {@link MigrationConventionTest}; that list may only
 * shrink.
 */
final class MigrationConventionChecker {

  static final String INDEX_CONCURRENTLY = "indexConcurrently";
  static final String CONCURRENTLY_CONF = "concurrentlyConf";
  static final String CONCURRENTLY_ONLY = "concurrentlyOnly";
  static final String LOCK_TIMEOUT = "lockTimeout";
  static final String TABLE_REWRITE = "tableRewrite";
  static final String UNBATCHED_UPDATE = "unbatchedUpdate";
  static final String H2_TWIN = "h2Twin";
  static final String H2_SYNTAX = "h2Syntax";

  private static final Pattern FILE_VERSION = Pattern.compile("^V(\\d+)(?:__.*)?\\.sql$");
  private static final Pattern JAVA_VERSION = Pattern.compile("^V(\\d+)\\w*\\.java$");
  private static final Pattern MARKER =
      Pattern.compile("--\\s*migration-check:\\s*allow\\s+(\\w+)\\s+(\\S.*)");
  private static final String NAME = "(?:\"?\\w+\"?\\.)?\"?(\\w+)\"?";
  private static final int FLAGS = Pattern.CASE_INSENSITIVE;
  private static final Pattern CREATE_TABLE =
      Pattern.compile("create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?" + NAME, FLAGS);
  private static final Pattern CREATE_INDEX =
      Pattern.compile(
          "create\\s+(?:unique\\s+)?index\\s+(concurrently\\s+)?(?:if\\s+not\\s+exists\\s+)?"
              + "(?:\"?\\w+\"?\\s+)?on\\s+(?:only\\s+)?"
              + NAME,
          FLAGS);
  private static final Pattern DROP_INDEX =
      Pattern.compile("drop\\s+index\\s+(concurrently\\s+)?", FLAGS);
  private static final Pattern ALTER_TABLE =
      Pattern.compile(
          "alter\\s+table\\s+(?:if\\s+exists\\s+)?(?:only\\s+)?" + NAME + "\\s+([^;]*)", FLAGS);
  private static final Pattern LOCKING_ALTER =
      Pattern.compile(
          "add\\s+column|drop\\s+column|set\\s+not\\s+null|add\\s+(?:constraint|foreign|check|unique"
              + "|primary)|alter\\s+column\\s+\\S+\\s+(?:set\\s+data\\s+)?type",
          FLAGS);
  private static final Pattern REWRITE =
      Pattern.compile(
          "alter\\s+column\\s+\\S+\\s+(?:set\\s+data\\s+)?type|vacuum\\s+full|\\bcluster\\s+\\S",
          FLAGS);
  private static final Pattern UPDATE =
      Pattern.compile("^\\s*update\\s+(?:only\\s+)?" + NAME, FLAGS);
  private static final Pattern LOCK_TIMEOUT_SET =
      Pattern.compile("set\\s+(?:local\\s+)?lock_timeout\\s*(?:=|to)", FLAGS);
  private static final Pattern CONCURRENT_STATEMENT =
      Pattern.compile(
          "^(?:create\\s+(?:unique\\s+)?index\\s+concurrently\\s+if\\s+not\\s+exists"
              + "|drop\\s+index\\s+concurrently\\s+if\\s+exists)\\s.*",
          FLAGS | Pattern.DOTALL);
  private static final Pattern H2_FORBIDDEN =
      Pattern.compile(
          "\\bconcurrently\\b|create\\s+extension|using\\s+(?:gin|gist|brin)|lock_timeout|pg_trgm"
              + "|\\bcreate\\s+(?:unique\\s+)?index\\b[^;]*\\bwhere\\b",
          FLAGS);

  record Violation(String file, String rule, String message) {

    String key() {
      return file + ":" + rule;
    }

    @Override
    public String toString() {
      return file + " [" + rule + "] " + message;
    }
  }

  private MigrationConventionChecker() {}

  /**
   * @param postgresqlDir directory of the PostgreSQL scripts (the only one in Cloud)
   * @param h2Dir directory of the H2 scripts, or null when the repository has one dialect
   * @param h2JavaDir directory of the Java migrations of the H2 chain, or null
   */
  static List<Violation> check(Path postgresqlDir, Path h2Dir, Path h2JavaDir) {
    List<Violation> violations = new ArrayList<>();
    TreeMap<String, Path> pg = scripts(postgresqlDir);
    for (Path file : pg.values()) {
      checkPostgres(file, violations);
    }
    if (h2Dir != null) {
      checkH2Twins(pg, h2Dir, h2JavaDir, violations);
    }
    violations.sort(
        (a, b) ->
            a.file().equals(b.file())
                ? a.rule().compareTo(b.rule())
                : a.file().compareTo(b.file()));
    return violations;
  }

  private static void checkPostgres(Path file, List<Violation> out) {
    String name = file.getFileName().toString();
    String raw = read(file);
    Set<String> allowed = new HashSet<>();
    Matcher marker = MARKER.matcher(raw);
    while (marker.find()) {
      allowed.add(marker.group(1));
    }
    String sql = stripComments(raw);
    Set<String> created = new HashSet<>();
    Matcher ct = CREATE_TABLE.matcher(sql);
    while (ct.find()) {
      created.add(ct.group(1).toLowerCase(Locale.ROOT));
    }
    List<Violation> found = new ArrayList<>();

    boolean concurrently = false;
    Matcher ci = CREATE_INDEX.matcher(sql);
    while (ci.find()) {
      if (ci.group(1) != null) {
        concurrently = true;
      } else if (!created.contains(ci.group(2).toLowerCase(Locale.ROOT))) {
        found.add(
            new Violation(
                name,
                INDEX_CONCURRENTLY,
                "CREATE INDEX on existing table " + ci.group(2) + " must be CONCURRENTLY"));
      }
    }
    Matcher di = DROP_INDEX.matcher(sql);
    while (di.find()) {
      if (di.group(1) != null) {
        concurrently = true;
      } else {
        found.add(new Violation(name, INDEX_CONCURRENTLY, "DROP INDEX must be CONCURRENTLY"));
      }
    }

    if (concurrently) {
      Path conf = file.resolveSibling(name + ".conf");
      if (!Files.isRegularFile(conf)
          || !read(conf).matches("(?s).*(?m)^\\s*executeInTransaction\\s*=\\s*false\\s*$.*")) {
        found.add(
            new Violation(
                name,
                CONCURRENTLY_CONF,
                name + ".conf with executeInTransaction=false is missing"));
      }
      for (String statement : sql.split(";")) {
        String s = statement.strip();
        if (!s.isEmpty() && !CONCURRENT_STATEMENT.matcher(s).matches()) {
          found.add(
              new Violation(
                  name,
                  CONCURRENTLY_ONLY,
                  "a CONCURRENTLY file holds only CREATE/DROP INDEX CONCURRENTLY IF [NOT] EXISTS,"
                      + " found: "
                      + abbreviate(s)));
          break;
        }
      }
    }

    boolean lockTimeout = LOCK_TIMEOUT_SET.matcher(sql).find();
    Matcher at = ALTER_TABLE.matcher(sql);
    while (at.find()) {
      String table = at.group(1).toLowerCase(Locale.ROOT);
      if (created.contains(table)) {
        continue;
      }
      if (!lockTimeout && LOCKING_ALTER.matcher(at.group(2)).find()) {
        found.add(
            new Violation(
                name,
                LOCK_TIMEOUT,
                "ALTER TABLE " + table + " takes a lock: SET lock_timeout first"));
        lockTimeout = true; // one finding per file is enough
      }
    }
    Matcher rw = REWRITE.matcher(sql);
    if (rw.find()) {
      found.add(
          new Violation(
              name, TABLE_REWRITE, "may rewrite a table or change a column type: " + rw.group()));
    }
    for (String statement : sql.split(";")) {
      Matcher up = UPDATE.matcher(statement);
      if (up.find()
          && !created.contains(up.group(1).toLowerCase(Locale.ROOT))
          && !Pattern.compile("\\bwhere\\b", FLAGS).matcher(statement).find()) {
        found.add(
            new Violation(
                name,
                UNBATCHED_UPDATE,
                "UPDATE of " + up.group(1) + " without WHERE rewrites every row in one statement"));
        break;
      }
    }
    found.stream().filter(v -> !allowed.contains(v.rule())).forEach(out::add);
  }

  private static void checkH2Twins(
      TreeMap<String, Path> pg, Path h2Dir, Path h2JavaDir, List<Violation> out) {
    TreeMap<String, Path> h2 = scripts(h2Dir);
    Set<String> javaVersions = new HashSet<>();
    if (h2JavaDir != null && Files.isDirectory(h2JavaDir)) {
      try (Stream<Path> files = Files.list(h2JavaDir)) {
        files.forEach(
            f -> {
              Matcher m = JAVA_VERSION.matcher(f.getFileName().toString());
              if (m.matches()) {
                javaVersions.add(m.group(1));
              }
            });
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    for (var entry : pg.entrySet()) {
      if (!h2.containsKey(entry.getKey()) && !javaVersions.contains(entry.getKey())) {
        out.add(
            new Violation(
                entry.getValue().getFileName().toString(), H2_TWIN, "no H2 script or Java twin"));
      }
    }
    for (var entry : h2.entrySet()) {
      String name = entry.getValue().getFileName().toString();
      if (!pg.containsKey(entry.getKey())) {
        out.add(new Violation(name, H2_TWIN, "H2 script without a PostgreSQL twin"));
      }
      Matcher m = H2_FORBIDDEN.matcher(stripComments(read(entry.getValue())));
      if (m.find()) {
        out.add(
            new Violation(name, H2_SYNTAX, "PostgreSQL-only syntax in the H2 twin: " + m.group()));
      }
    }
  }

  /** Scripts of a directory by numeric version (V0007 and V7 are the same version). */
  private static TreeMap<String, Path> scripts(Path dir) {
    TreeMap<String, Path> result = new TreeMap<>();
    try (Stream<Path> files = Files.list(dir)) {
      files.forEach(
          f -> {
            Matcher m = FILE_VERSION.matcher(f.getFileName().toString());
            if (m.matches()) {
              result.put(String.format("%08d", Long.parseLong(m.group(1))), f);
            }
          });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return result;
  }

  static String stripComments(String sql) {
    return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\n]*", " ");
  }

  private static String abbreviate(String s) {
    String oneLine = s.replaceAll("\\s+", " ");
    return oneLine.length() > 70 ? oneLine.substring(0, 70) + "..." : oneLine;
  }

  private static String read(Path p) {
    try {
      return Files.readString(p, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
