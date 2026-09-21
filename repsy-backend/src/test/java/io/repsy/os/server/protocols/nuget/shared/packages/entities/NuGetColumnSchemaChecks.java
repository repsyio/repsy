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
package io.repsy.os.server.protocols.nuget.shared.packages.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Compares the {@link Column} annotations of the NuGet entities with the columns Flyway created.
 * Hibernate does not validate them ({@code ddl-auto: none}), so without this check a length in an
 * annotation can drift from the schema and mislead whoever reads it (RPS-1069).
 *
 * <p>Every {@code String} field is checked. A column the schema makes {@code varchar} must carry
 * its length and no {@code columnDefinition}. An unbounded column ({@code text} or {@code clob})
 * has no length to state, so the annotation names it in {@code columnDefinition} instead of
 * carrying the default length of 255 (except a column listed in {@link #TEXT_BOUNDED_IN_H2}). Any
 * other type ({@code jsonb}) must be named in {@code columnDefinition}.
 */
final class NuGetColumnSchemaChecks {

  private static final String VARCHAR = "CHARACTER VARYING";
  private static final String TEXT = "TEXT";
  private static final String CLOB = "CHARACTER LARGE OBJECT";

  private NuGetColumnSchemaChecks() {}

  /**
   * Columns that are {@code text} in PostgreSQL but bounded in H2. The annotation states the
   * smaller H2 limit, which is also the one {@code NuGetPackageUtils} cuts the value to, so a
   * length on them is not stale.
   */
  private static final Set<String> TEXT_BOUNDED_IN_H2 = Set.of("nuget_package_version.tags");

  private record DbColumn(String dataType, @Nullable Long maxLength) {}

  /** What PostgreSQL created: the annotated length must be exactly the {@code varchar} length. */
  static List<String> postgresProblems(final JdbcTemplate jdbc, final Class<?> entity) {
    return problems(jdbc, entity, false);
  }

  /**
   * What H2 created. H2 and PostgreSQL differ for a few columns, and the annotation states the
   * smaller limit for those, so it must not exceed the {@code varchar} length.
   */
  static List<String> h2Problems(final JdbcTemplate jdbc, final Class<?> entity) {
    return problems(jdbc, entity, true);
  }

  private static List<String> problems(
      final JdbcTemplate jdbc, final Class<?> entity, final boolean lengthMayBeSmaller) {

    final var table = entity.getAnnotation(Table.class).name();
    final var columns = readColumns(jdbc, table);
    final var problems = new ArrayList<String>();

    final var fields =
        Arrays.stream(entity.getDeclaredFields())
            .filter(f -> f.getType() == String.class && f.isAnnotationPresent(Column.class))
            .toList();
    if (fields.isEmpty()) {
      problems.add(entity.getSimpleName() + " has no annotated String field, the check is empty");
    }

    for (final var field : fields) {
      final var annotation = field.getAnnotation(Column.class);
      final var db = columns.get(annotation.name().toLowerCase(Locale.ROOT));
      if (db == null) {
        problems.add(describe(entity, field, table) + ": the schema has no such column");
        continue;
      }
      final var problem = check(table, annotation, db, lengthMayBeSmaller);
      if (problem != null) {
        problems.add(describe(entity, field, table) + ": " + problem);
      }
    }
    return problems;
  }

  private static @Nullable String check(
      final String table,
      final Column annotation,
      final DbColumn db,
      final boolean lengthMayBeSmaller) {

    final var definition = annotation.columnDefinition();
    if (VARCHAR.equals(db.dataType())) {
      return checkVarchar(annotation, db, lengthMayBeSmaller);
    }
    if (TEXT.equals(db.dataType()) || CLOB.equals(db.dataType())) {
      final var boundedInH2 = TEXT_BOUNDED_IN_H2.contains(table + "." + annotation.name());
      return definition.isEmpty() && !boundedInH2
          ? ("the schema column is unbounded (%s) but the annotation states length=%d;"
                  + " name the type in columnDefinition")
              .formatted(db.dataType().toLowerCase(Locale.ROOT), annotation.length())
          : null;
    }
    return definition.equalsIgnoreCase(db.dataType())
        ? null
        : "the schema column is %s but columnDefinition is \"%s\""
            .formatted(db.dataType().toLowerCase(Locale.ROOT), definition);
  }

  private static @Nullable String checkVarchar(
      final Column annotation, final DbColumn db, final boolean lengthMayBeSmaller) {

    if (!annotation.columnDefinition().isEmpty()) {
      return "the schema column is varchar(%d) but the annotation sets columnDefinition \"%s\""
          .formatted(db.maxLength(), annotation.columnDefinition());
    }
    final var max = db.maxLength();
    if (max == null) {
      return "the schema column is varchar without a length";
    }
    final var fits = lengthMayBeSmaller ? annotation.length() <= max : annotation.length() == max;
    return fits
        ? null
        : "the schema column is varchar(%d) but the annotation says length=%d"
            .formatted(max, annotation.length());
  }

  private static String describe(final Class<?> entity, final Field field, final String table) {
    return "%s.%s (column %s.%s)"
        .formatted(
            entity.getSimpleName(),
            field.getName(),
            table,
            field.getAnnotation(Column.class).name());
  }

  private static Map<String, DbColumn> readColumns(final JdbcTemplate jdbc, final String table) {
    final var columns = new HashMap<String, DbColumn>();
    jdbc.query(
        """
        select lower(column_name), upper(data_type), character_maximum_length
          from information_schema.columns
         where lower(table_schema) = 'public' and lower(table_name) = ?
        """,
        rs -> {
          // H2 reports Long.MAX_VALUE for a clob.
          final var raw = rs.getLong(3);
          final var length = rs.wasNull() ? null : raw;
          columns.put(rs.getString(1), new DbColumn(rs.getString(2), length));
        },
        table);
    return columns;
  }
}
