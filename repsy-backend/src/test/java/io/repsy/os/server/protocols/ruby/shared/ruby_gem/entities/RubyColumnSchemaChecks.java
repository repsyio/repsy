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
package io.repsy.os.server.protocols.ruby.shared.ruby_gem.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Compares the {@link Column} annotations of the Ruby entities with the columns Flyway created.
 * Hibernate does not validate them ({@code ddl-auto: none}), so without this check a length in an
 * annotation can drift from the schema. For these entities the length is also the limit {@code
 * GemspecParser} applies to a pushed gem, so a stale one would let a value through that the
 * database refuses (RPS-1071, after RPS-1069 did the same for NuGet).
 *
 * <p>Every {@code String} field is checked. A column the schema makes {@code varchar} must carry
 * exactly its length and no {@code columnDefinition}; PostgreSQL and H2 create the same lengths for
 * the Ruby tables. An unbounded column ({@code text} or {@code clob}) has no length to state, so
 * the annotation names it in {@code columnDefinition} instead of carrying the default length of
 * 255. Any other type must be named in {@code columnDefinition}.
 */
final class RubyColumnSchemaChecks {

  private static final String VARCHAR = "CHARACTER VARYING";
  private static final String TEXT = "TEXT";
  private static final String CLOB = "CHARACTER LARGE OBJECT";

  private RubyColumnSchemaChecks() {}

  private record DbColumn(String dataType, @Nullable Long maxLength) {}

  /** The annotations of {@code entity} that differ from the columns of its table. */
  static List<String> problems(final JdbcTemplate jdbc, final Class<?> entity) {
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
      final var problem = check(annotation, db);
      if (problem != null) {
        problems.add(describe(entity, field, table) + ": " + problem);
      }
    }
    return problems;
  }

  private static @Nullable String check(final Column annotation, final DbColumn db) {
    final var definition = annotation.columnDefinition();
    if (VARCHAR.equals(db.dataType())) {
      return checkVarchar(annotation, db);
    }
    if (TEXT.equals(db.dataType()) || CLOB.equals(db.dataType())) {
      return definition.isEmpty()
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

  private static @Nullable String checkVarchar(final Column annotation, final DbColumn db) {
    if (!annotation.columnDefinition().isEmpty()) {
      return "the schema column is varchar(%d) but the annotation sets columnDefinition \"%s\""
          .formatted(db.maxLength(), annotation.columnDefinition());
    }
    final var max = db.maxLength();
    if (max == null) {
      return "the schema column is varchar without a length";
    }
    return annotation.length() == max
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
