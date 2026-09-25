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

import jakarta.persistence.Column;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Compares the column mappings of every JPA entity with the columns Flyway created. Hibernate does
 * not validate them ({@code ddl-auto: none}), so without this check an annotation can drift from
 * the schema and either mislead whoever reads it or let a value through that the database refuses
 * (RPS-1069 for NuGet, RPS-1071 for Ruby, RPS-1133 for everything else).
 *
 * <p>The entities come from the Hibernate metamodel, so a new entity is checked without being
 * listed anywhere. For each mapped column:
 *
 * <ul>
 *   <li>the column must exist;
 *   <li>a {@code String} column the schema makes {@code varchar} must carry its length and no
 *       {@code columnDefinition};
 *   <li>a {@code String} column the schema makes unbounded ({@code text}, or {@code clob} in H2)
 *       has no length to state, so the annotation says {@code columnDefinition = "text"} instead of
 *       carrying the default length of 255. That is the one convention for unbounded columns: not
 *       {@code "clob"}, and not {@code @Lob}, which changes how Hibernate binds the value;
 *   <li>any other type named in {@code columnDefinition} ({@code uuid}, {@code jsonb}) must be the
 *       type of the column;
 *   <li>an {@code @Enumerated(STRING)} column must be able to hold the longest constant;
 *   <li>{@code nullable} must agree with the schema: a column the schema makes {@code NOT NULL} is
 *       declared {@code nullable = false} (a primitive or an id needs no declaration), and the
 *       annotation never declares {@code nullable = false} for a column the schema lets be null.
 * </ul>
 */
public final class EntityColumnSchemaChecks {

  private static final String VARCHAR = "CHARACTER VARYING";
  private static final String TEXT = "TEXT";
  private static final String CLOB = "CHARACTER LARGE OBJECT";
  private static final String TEXT_DEFINITION = "text";
  private static final String JSONB_DEFINITION = "jsonb";
  private static final long H2_MAX_VARCHAR = 1_000_000_000L;

  /**
   * Columns that are {@code text} in PostgreSQL but bounded in H2. The annotation states the
   * smaller H2 limit, which is also the one the code cuts the value to, so a length on them is not
   * stale.
   */
  private static final Set<String> TEXT_BOUNDED_IN_H2 = Set.of("nuget_package_version.tags");

  /**
   * Columns that are {@code text} in PostgreSQL but {@code varchar(n)} in H2, with that {@code n}.
   * The annotation names the PostgreSQL type. A value that fits PostgreSQL can be refused by H2, so
   * the limit is recorded here rather than left to be found by an insert; where the code bounds the
   * value below {@code n} anyway, nothing else is needed.
   */
  private static final Map<String, Long> H2_BOUNDED_TEXT =
      Map.of(
          "cargo_keyword.keyword", 100L,
          "cargo_author.author", 255L,
          "cargo_category.category", 255L,
          "cargo_crate_index.name", 255L,
          "cargo_crate_index.cksum", 255L,
          "cargo_crate_index.links", 255L);

  private EntityColumnSchemaChecks() {}

  /** Which database the schema was created on. */
  public enum Dialect {
    /** The annotated length must be exactly the {@code varchar} length. */
    POSTGRESQL,
    /**
     * H2 and PostgreSQL differ for a few columns, and the annotation states the smaller limit for
     * those, so it must not exceed the {@code varchar} length.
     */
    H2
  }

  private record DbColumn(String dataType, @Nullable Long maxLength, boolean nullable) {}

  /** One mapped column of an entity: the field, its column name and its annotations. */
  private record Mapping(
      Class<?> entity,
      String table,
      Field field,
      String column,
      @Nullable Column annotation,
      @Nullable JoinColumn joinColumn,
      boolean id) {

    String describe() {
      return "%s.%s (column %s.%s)"
          .formatted(entity.getSimpleName(), field.getName(), table, column);
    }

    boolean annotatedNullable() {
      if (annotation != null) {
        return annotation.nullable();
      }
      return joinColumn == null || joinColumn.nullable();
    }

    boolean impliedNotNull() {
      return id || field.getType().isPrimitive();
    }
  }

  /** The entity classes the persistence unit maps, sorted by name. */
  public static List<Class<?>> entities(final EntityManagerFactory entityManagerFactory) {
    return entityManagerFactory.getMetamodel().getEntities().stream()
        .<Class<?>>map(e -> e.getJavaType())
        .sorted(Comparator.comparing(Class::getName))
        .toList();
  }

  /** The problems of every entity, or an empty list when all their mappings match the schema. */
  public static List<String> problems(
      final JdbcTemplate jdbc,
      final EntityManagerFactory entityManagerFactory,
      final Dialect dialect) {

    final var problems = new ArrayList<String>();
    for (final var entity : entities(entityManagerFactory)) {
      problems.addAll(problems(jdbc, entity, dialect));
    }
    return problems;
  }

  /**
   * The problems of one entity. The mapping is read from the annotations of its fields, and of the
   * fields it inherits from a mapped superclass.
   */
  public static List<String> problems(
      final JdbcTemplate jdbc, final Class<?> entity, final Dialect dialect) {

    final var table = entity.getAnnotation(Table.class).name();
    final var columns = readColumns(jdbc, table);
    final var problems = new ArrayList<String>();
    for (final var mapping : mappings(entity, table)) {
      final var db = columns.get(mapping.column().toLowerCase(Locale.ROOT));
      if (db == null) {
        problems.add(mapping.describe() + ": the schema has no such column");
      } else {
        check(mapping, db, dialect).forEach(p -> problems.add(mapping.describe() + ": " + p));
      }
    }
    return problems;
  }

  private static List<Mapping> mappings(final Class<?> entity, final String table) {
    final var mappings = new ArrayList<Mapping>();
    for (var type = entity; type != null && type != Object.class; type = type.getSuperclass()) {
      for (final var field : type.getDeclaredFields()) {
        final var column = field.getAnnotation(Column.class);
        final var join = field.getAnnotation(JoinColumn.class);
        if (column != null || join != null) {
          final var id = field.isAnnotationPresent(Id.class);
          mappings.add(
              new Mapping(entity, table, field, columnName(field, column, join), column, join, id));
        }
      }
    }
    return mappings;
  }

  private static String columnName(
      final Field field, final @Nullable Column column, final @Nullable JoinColumn join) {

    if (column != null && !column.name().isEmpty()) {
      return column.name();
    }
    if (join != null && !join.name().isEmpty()) {
      return join.name();
    }
    // Spring's CamelCaseToUnderscoresNamingStrategy, which the application uses.
    return field.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
  }

  private static List<String> check(
      final Mapping mapping, final DbColumn db, final Dialect dialect) {

    final var problems = new ArrayList<String>();
    if (mapping.field().isAnnotationPresent(Lob.class)) {
      // RPS-1392: a @Lob String is bound as a CLOB, which stores a large-object OID in a PostgreSQL
      // text column (helm_oci_manifest.content did).
      problems.add(
          "the mapping uses @Lob; an unbounded column is columnDefinition \"text\" instead");
    }
    final var type = typeProblem(mapping, db, dialect);
    if (type != null) {
      problems.add(type);
    }
    final var nullability = nullabilityProblem(mapping, db);
    if (nullability != null) {
      problems.add(nullability);
    }
    return problems;
  }

  private static @Nullable String typeProblem(
      final Mapping mapping, final DbColumn db, final Dialect dialect) {

    final var annotation = mapping.annotation();
    if (annotation == null) {
      return null;
    }
    if (mapping.field().getType().isEnum()) {
      return enumProblem(mapping, db);
    }
    if (mapping.field().getType() != String.class) {
      return columnDefinitionProblem(annotation, db);
    }
    return stringProblem(mapping, annotation, db, dialect);
  }

  private static @Nullable String stringProblem(
      final Mapping mapping, final Column annotation, final DbColumn db, final Dialect dialect) {

    if (isUnbounded(db, dialect)) {
      return unboundedProblem(mapping, annotation, db, dialect);
    }
    if (VARCHAR.equals(db.dataType())) {
      return dialect == Dialect.H2 && H2_BOUNDED_TEXT.containsKey(key(mapping))
          ? h2BoundedTextProblem(mapping, annotation, db)
          : varcharProblem(annotation, db, dialect);
    }
    return columnDefinitionProblem(annotation, db);
  }

  private static boolean isUnbounded(final DbColumn db, final Dialect dialect) {
    if (TEXT.equals(db.dataType()) || CLOB.equals(db.dataType())) {
      return true;
    }
    // H2 creates text as a varchar of its maximum length.
    return dialect == Dialect.H2
        && VARCHAR.equals(db.dataType())
        && db.maxLength() != null
        && db.maxLength() >= H2_MAX_VARCHAR;
  }

  private static String key(final Mapping mapping) {
    return mapping.table() + "." + mapping.column();
  }

  /**
   * The annotation names the PostgreSQL type, and the H2 script must still create the limit that
   * {@link #H2_BOUNDED_TEXT} records, so a change of that script is noticed here.
   */
  private static @Nullable String h2BoundedTextProblem(
      final Mapping mapping, final Column annotation, final DbColumn db) {

    final var expected = H2_BOUNDED_TEXT.get(key(mapping));
    if (!TEXT_DEFINITION.equalsIgnoreCase(annotation.columnDefinition())) {
      return "the column is text in PostgreSQL, so the annotation must set columnDefinition \"text\"";
    }
    return expected.equals(db.maxLength())
        ? null
        : "H2_BOUNDED_TEXT expects varchar(%d) in H2 but the schema column is varchar(%d)"
            .formatted(expected, db.maxLength());
  }

  /**
   * An unbounded column has no length to state, so the annotation names the type instead of
   * carrying the default length of 255. The name is always {@code text}, the PostgreSQL type: it
   * has no runtime effect, whereas {@code @Lob} changes how Hibernate binds the value.
   */
  private static @Nullable String unboundedProblem(
      final Mapping mapping, final Column annotation, final DbColumn db, final Dialect dialect) {

    // H2 has no jsonb and stores it as a clob; PostgreSQL is checked to be jsonb.
    final var jsonInH2 =
        dialect == Dialect.H2 && JSONB_DEFINITION.equalsIgnoreCase(annotation.columnDefinition());
    if (jsonInH2
        || TEXT_BOUNDED_IN_H2.contains(key(mapping))
        || TEXT_DEFINITION.equalsIgnoreCase(annotation.columnDefinition())) {
      return null;
    }
    return ("the schema column is unbounded (%s) but the annotation says length=%d,"
            + " columnDefinition \"%s\"; unbounded columns state columnDefinition \"text\"")
        .formatted(
            db.dataType().toLowerCase(Locale.ROOT),
            annotation.length(),
            annotation.columnDefinition());
  }

  private static @Nullable String varcharProblem(
      final Column annotation, final DbColumn db, final Dialect dialect) {

    if (!annotation.columnDefinition().isEmpty()) {
      return "the schema column is varchar(%d) but the annotation sets columnDefinition \"%s\""
          .formatted(db.maxLength(), annotation.columnDefinition());
    }
    final var max = db.maxLength();
    if (max == null) {
      return "the schema column is varchar without a length";
    }
    final var fits =
        dialect == Dialect.H2 ? annotation.length() <= max : annotation.length() == max;
    return fits
        ? null
        : "the schema column is varchar(%d) but the annotation says length=%d"
            .formatted(max, annotation.length());
  }

  private static @Nullable String columnDefinitionProblem(
      final Column annotation, final DbColumn db) {

    final var definition = annotation.columnDefinition();
    if (definition.isEmpty() || definition.equalsIgnoreCase(db.dataType())) {
      return null;
    }
    return "the schema column is %s but columnDefinition is \"%s\""
        .formatted(db.dataType().toLowerCase(Locale.ROOT), definition);
  }

  private static @Nullable String enumProblem(final Mapping mapping, final DbColumn db) {
    final var longest =
        Arrays.stream(mapping.field().getType().getEnumConstants())
            .mapToInt(c -> ((Enum<?>) c).name().length())
            .max()
            .orElse(0);
    if (VARCHAR.equals(db.dataType()) && db.maxLength() != null && db.maxLength() < longest) {
      return "the schema column is varchar(%d) but the longest constant of %s has %d characters"
          .formatted(db.maxLength(), mapping.field().getType().getSimpleName(), longest);
    }
    return null;
  }

  private static @Nullable String nullabilityProblem(final Mapping mapping, final DbColumn db) {
    if (db.nullable() && !mapping.annotatedNullable()) {
      return "the annotation says nullable=false but the schema column allows null";
    }
    if (!db.nullable() && mapping.annotatedNullable() && !mapping.impliedNotNull()) {
      return "the schema column is NOT NULL but the annotation does not say nullable=false";
    }
    return null;
  }

  private static Map<String, DbColumn> readColumns(final JdbcTemplate jdbc, final String table) {
    final var columns = new HashMap<String, DbColumn>();
    jdbc.query(
        """
        select lower(column_name), upper(data_type), character_maximum_length, is_nullable
          from information_schema.columns
         where lower(table_schema) = 'public' and lower(table_name) = ?
        """,
        rs -> {
          // H2 reports Long.MAX_VALUE for a clob.
          final var raw = rs.getLong(3);
          final var length = rs.wasNull() ? null : raw;
          columns.put(
              rs.getString(1),
              new DbColumn(rs.getString(2), length, "YES".equalsIgnoreCase(rs.getString(4))));
        },
        table);
    return columns;
  }
}
