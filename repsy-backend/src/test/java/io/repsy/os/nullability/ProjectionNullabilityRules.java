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
package io.repsy.os.nullability;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.SingularAttribute;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.SelectableMapping;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The null rule of an interface projection (RPS-2077). Under a package-level {@code @NullMarked}
 * Spring Data also guards the getters of a projection proxy: a getter that is not {@code @Nullable}
 * and answers null throws "Return value is null but must not be null", so the whole list answers
 * 500 (the Docker {@code ImageListItem#getDigest} of an image without tags).
 *
 * <p>For every getter of a projection that a repository method returns (also inside {@code Page},
 * {@code List}, {@code Optional}), the backing expression is derived from the HQL select clause and
 * the DB column behind it is asked from {@code information_schema}. The getter must be
 * {@code @Nullable} when its column is nullable, when it is read through a {@code left join} or an
 * optional association, or when it is an aggregate or expression that this rule cannot prove
 * non-null. A getter whose expression is unknown must be {@code @Nullable} or listed in the {@code
 * FROZEN} map with the reason it cannot be null.
 *
 * <p>Limits: only HQL {@code select alias.path as name} items (also inside {@code cast}), the
 * entity itself ({@code select x from X x}, derived queries) and {@code count(...)} are derived. A
 * native query, a {@code case}/{@code coalesce}/function expression and an aggregate are "unknown".
 * Primitive getters are skipped (null is a conversion error whatever the marking). Record and class
 * DTOs built by {@code select new} are not checked (Spring Data does not guard them). The DB is the
 * PostgreSQL schema; the H2 scripts are not read.
 */
final class ProjectionNullabilityRules {

  private static final Pattern PATH = Pattern.compile("\\w+(\\.\\w+)+");
  private static final Pattern CAST =
      Pattern.compile("cast\\s*\\((.+)\\s+as\\s+\\w+\\)", Pattern.CASE_INSENSITIVE);
  private static final Pattern COUNT = Pattern.compile("count\\s*\\(.*", Pattern.CASE_INSENSITIVE);
  private static final Pattern FROM_ALIAS =
      Pattern.compile("\\bfrom\\s+(\\w+)\\s+(?:as\\s+)?(\\w+)", Pattern.CASE_INSENSITIVE);
  private static final Pattern PATH_JOIN =
      Pattern.compile(
          "(left\\s+(?:outer\\s+)?|right\\s+(?:outer\\s+)?|inner\\s+)?join\\s+(?:fetch\\s+)?"
              + "(\\w+)\\.(\\w+)\\s+(?:as\\s+)?(\\w+)",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern ENTITY_JOIN =
      Pattern.compile(
          "(left\\s+(?:outer\\s+)?|right\\s+(?:outer\\s+)?|inner\\s+)?join\\s+(\\w+)\\s+"
              + "(?:as\\s+)?(\\w+)\\s+on\\b",
          Pattern.CASE_INSENSITIVE);
  private static final List<String> KEYWORDS =
      List.of("where", "join", "left", "right", "inner", "order", "group", "on", "with");

  /** How sure the rule is that a getter can be null. */
  enum Verdict {
    NOT_NULL,
    NULLABLE,
    UNKNOWN
  }

  /** One getter of a projection and what the rule derived. */
  record Finding(
      Class<?> projection,
      String getter,
      boolean annotated,
      Verdict verdict,
      String backing,
      String reason,
      Method repositoryMethod) {

    String key() {
      return this.projection.getName() + "#" + this.getter;
    }

    boolean violates() {
      return !this.annotated && this.verdict != Verdict.NOT_NULL;
    }

    @Override
    public String toString() {
      return key()
          + " ("
          + this.backing
          + "): "
          + this.reason
          + ". Fix: annotate the getter with @org.jspecify.annotations.Nullable"
          + (this.verdict == Verdict.UNKNOWN ? ", or add it to FROZEN with the proof" : "");
    }
  }

  private record Alias(Class<?> entity, boolean nullableJoin) {}

  private final EntityManagerFactory entityManagerFactory;
  private final SessionFactoryImplementor sessionFactory;
  private final JdbcTemplate jdbc;
  private final Map<String, Class<?>> entities = new HashMap<>();

  ProjectionNullabilityRules(final EntityManagerFactory emf, final JdbcTemplate jdbc) {
    this.entityManagerFactory = emf;
    this.sessionFactory = emf.unwrap(SessionFactoryImplementor.class);
    this.jdbc = jdbc;

    for (final EntityType<?> type : emf.getMetamodel().getEntities()) {
      this.entities.put(type.getName(), type.getJavaType());
    }
  }

  /** The findings of every getter of every projection the repository returns. */
  List<Finding> check(final Class<?> repository, final boolean onlyNullMarked) {
    final var findings = new ArrayList<Finding>();
    final var domain = ResolvableType.forClass(repository).as(Repository.class).getGeneric(0);

    for (final var method : repository.getDeclaredMethods()) {
      if (method.isSynthetic() || method.isBridge()) {
        continue;
      }

      for (final var projection : projectionsOf(ResolvableType.forMethodReturnType(method))) {
        if (onlyNullMarked && !isNullMarked(projection)) {
          continue;
        }

        final var query = AnnotatedElementUtils.findMergedAnnotation(method, Query.class);

        for (final var getter : projection.getMethods()) {
          if (isGetter(getter)) {
            findings.add(finding(projection, getter, method, query, domain.resolve()));
          }
        }
      }
    }

    return findings;
  }

  private static List<Class<?>> projectionsOf(final ResolvableType type) {
    final var found = new ArrayList<Class<?>>();
    final var resolved = type.resolve();

    if (resolved != null
        && resolved.isInterface()
        && !resolved.getPackageName().startsWith("java.")
        && !resolved.getPackageName().startsWith("org.springframework.")
        && !resolved.isAnnotationPresent(jakarta.persistence.Entity.class)
        && !Repository.class.isAssignableFrom(resolved)) {
      found.add(resolved);
    }

    for (final var generic : type.getGenerics()) {
      found.addAll(projectionsOf(generic));
    }

    return found;
  }

  static boolean isNullMarked(final Class<?> type) {
    for (Class<?> c = type; c != null; c = c.getEnclosingClass()) {
      if (c.isAnnotationPresent(NullMarked.class)) {
        return true;
      }
    }

    return type.getPackage().isAnnotationPresent(NullMarked.class);
  }

  private static boolean isGetter(final Method method) {
    final var name = method.getName();

    return method.getParameterCount() == 0
        && !method.getReturnType().isPrimitive()
        && !method.isDefault()
        && !java.lang.reflect.Modifier.isStatic(method.getModifiers())
        && (name.startsWith("get") && name.length() > 3);
  }

  private static boolean isNullable(final Method getter) {
    return hasNullable(getter.getAnnotations())
        || hasNullable(getter.getAnnotatedReturnType().getAnnotations());
  }

  private static boolean hasNullable(final Annotation[] annotations) {
    return Arrays.stream(annotations)
        .anyMatch(annotation -> "Nullable".equals(annotation.annotationType().getSimpleName()));
  }

  private Finding finding(
      final Class<?> projection,
      final Method getter,
      final Method method,
      final Query query,
      final Class<?> domain) {
    final var property = decapitalize(getter.getName().substring(3));
    final var result = derive(property, query, domain);

    return new Finding(
        projection,
        getter.getName(),
        isNullable(getter),
        result.verdict(),
        result.backing(),
        result.reason(),
        method);
  }

  private record Derived(Verdict verdict, String backing, String reason) {}

  private Derived derive(final String property, final Query query, final Class<?> domain) {
    if (query == null) {
      return attribute(domain, property, false, "derived query of " + simple(domain));
    }

    if (query.nativeQuery()) {
      return new Derived(Verdict.UNKNOWN, "native query", "a native query is not derived");
    }

    final var hql = query.value().replaceAll("\\s+", " ").trim();
    final var fromAt = topLevel(hql, " from ");

    if (!hql.toLowerCase(Locale.ROOT).startsWith("select ") || fromAt < 0) {
      return new Derived(Verdict.UNKNOWN, "hql", "the select clause is not readable");
    }

    final var aliases = aliases(hql.substring(fromAt));
    var select = hql.substring("select ".length(), fromAt).trim();

    if (select.toLowerCase(Locale.ROOT).startsWith("distinct ")) {
      select = select.substring("distinct ".length());
    }

    final var items = splitTopLevel(select);

    if (items.size() == 1 && aliases.containsKey(items.get(0))) {
      final var root = aliases.get(items.get(0));
      return attribute(
          root.entity(),
          property,
          root.nullableJoin(),
          "entity " + simple(root.entity()) + " selected whole");
    }

    for (final var item : items) {
      final var as = lastTopLevel(item, " as ");

      if (as > 0 && item.substring(as + 4).trim().equals(property)) {
        return expression(item.substring(0, as).trim(), aliases);
      }
    }

    return new Derived(Verdict.UNKNOWN, "hql", "no select item is aliased '" + property + "'");
  }

  private Derived expression(final String expression, final Map<String, Alias> aliases) {
    final var cast = CAST.matcher(expression);

    if (cast.matches()) {
      return expression(cast.group(1).trim(), aliases);
    }

    if (COUNT.matcher(expression).matches()) {
      return new Derived(Verdict.NOT_NULL, expression, "count is never null");
    }

    if (PATH.matcher(expression).matches()) {
      final var parts = expression.split("\\.");
      var alias = aliases.get(parts[0]);

      if (alias == null) {
        return new Derived(Verdict.UNKNOWN, expression, "alias '" + parts[0] + "' is unknown");
      }

      var nullable = alias.nullableJoin();
      var entity = alias.entity();

      for (int i = 1; i < parts.length - 1; i++) {
        final var step = associationOf(entity, parts[i]);

        if (step == null) {
          return new Derived(Verdict.UNKNOWN, expression, "'" + parts[i] + "' is not mapped");
        }

        nullable |= step.isOptional();
        entity = step.getJavaType();
      }

      return attribute(entity, parts[parts.length - 1], nullable, expression);
    }

    return new Derived(
        Verdict.UNKNOWN, expression, "an expression or aggregate that is not proved non-null");
  }

  private SingularAttribute<?, ?> associationOf(final Class<?> entity, final String name) {
    try {
      final var attribute =
          this.entityManagerFactory.getMetamodel().entity(entity).getAttribute(name);

      return attribute instanceof SingularAttribute<?, ?> singular
              && singular.getType() instanceof EntityType<?>
          ? singular
          : null;
    } catch (final IllegalArgumentException e) {
      return null;
    }
  }

  private Derived attribute(
      final Class<?> entity, final String name, final boolean joinNullable, final String backing) {
    final jakarta.persistence.metamodel.Attribute<?, ?> attribute;

    try {
      attribute = this.entityManagerFactory.getMetamodel().entity(entity).getAttribute(name);
    } catch (final IllegalArgumentException e) {
      return new Derived(
          Verdict.UNKNOWN,
          backing + "." + name,
          simple(entity) + " has no attribute '" + name + "'");
    }

    final var where = backing.contains(" ") ? simple(entity) + "." + name : backing;

    if (joinNullable) {
      return new Derived(
          Verdict.NULLABLE, where, "read through a left join or an optional association");
    }

    final var persister = this.sessionFactory.getMappingMetamodel().getEntityDescriptor(entity);
    final var mapping = persister.findAttributeMapping(name);

    if (mapping instanceof SelectableMapping selectable
        && selectable.getContainingTableExpression() != null) {
      final var table = unquote(selectable.getContainingTableExpression());
      final var column = unquote(selectable.getSelectionExpression());
      final var nullable = columnIsNullable(table, column);

      if (nullable == null) {
        return new Derived(
            Verdict.UNKNOWN, where, "column " + table + "." + column + " not found in the schema");
      }

      return new Derived(
          nullable ? Verdict.NULLABLE : Verdict.NOT_NULL,
          where,
          "column " + table + "." + column + (nullable ? " is nullable" : " is NOT NULL"));
    }

    if (attribute instanceof SingularAttribute<?, ?> singular) {
      return new Derived(
          singular.isOptional() ? Verdict.NULLABLE : Verdict.NOT_NULL,
          where,
          "mapped attribute is " + (singular.isOptional() ? "optional" : "not optional"));
    }

    return new Derived(Verdict.UNKNOWN, where, "not a singular attribute");
  }

  private Boolean columnIsNullable(final String table, final String column) {
    final var rows =
        this.jdbc.queryForList(
            "select is_nullable from information_schema.columns "
                + "where table_schema = current_schema() and table_name = ? and column_name = ?",
            String.class,
            table,
            column);

    return rows.isEmpty() ? null : "YES".equals(rows.get(0));
  }

  private Map<String, Alias> aliases(final String fromClause) {
    final var aliases = new LinkedHashMap<String, Alias>();
    final var from = FROM_ALIAS.matcher(fromClause);

    while (from.find()) {
      final var entity = this.entities.get(from.group(1));
      final var alias = from.group(2);

      if (entity != null && !KEYWORDS.contains(alias.toLowerCase(Locale.ROOT))) {
        aliases.putIfAbsent(alias, new Alias(entity, false));
      }
    }

    final var entityJoin = ENTITY_JOIN.matcher(fromClause);

    while (entityJoin.find()) {
      final var entity = this.entities.get(entityJoin.group(2));

      if (entity != null) {
        aliases.putIfAbsent(entityJoin.group(3), new Alias(entity, outer(entityJoin.group(1))));
      }
    }

    final var join = PATH_JOIN.matcher(fromClause);

    while (join.find()) {
      final var parent = aliases.get(join.group(2));
      final var step = parent == null ? null : associationOf(parent.entity(), join.group(3));

      if (step == null) {
        final var plural = parent == null ? null : pluralTarget(parent.entity(), join.group(3));

        if (plural != null) {
          aliases.putIfAbsent(
              join.group(4), new Alias(plural, parent.nullableJoin() || outer(join.group(1))));
        }

        continue;
      }

      aliases.putIfAbsent(
          join.group(4),
          new Alias(
              step.getJavaType(),
              parent.nullableJoin() || outer(join.group(1)) || step.isOptional()));
    }

    return aliases;
  }

  private Class<?> pluralTarget(final Class<?> entity, final String name) {
    try {
      final var attribute =
          this.entityManagerFactory.getMetamodel().entity(entity).getAttribute(name);

      return attribute instanceof jakarta.persistence.metamodel.PluralAttribute<?, ?, ?> plural
          ? plural.getElementType().getJavaType()
          : null;
    } catch (final IllegalArgumentException e) {
      return null;
    }
  }

  private static boolean outer(final String joinKind) {
    return joinKind != null
        && (joinKind.toLowerCase(Locale.ROOT).startsWith("left")
            || joinKind.toLowerCase(Locale.ROOT).startsWith("right"));
  }

  /** The index of the first {@code token} outside parentheses and quotes, or -1. */
  private static int topLevel(final String text, final String token) {
    final var lower = text.toLowerCase(Locale.ROOT);
    var depth = 0;
    var quoted = false;

    for (int i = 0; i < lower.length(); i++) {
      final var c = lower.charAt(i);

      if (c == '\'') {
        quoted = !quoted;
      } else if (!quoted && c == '(') {
        depth++;
      } else if (!quoted && c == ')') {
        depth--;
      } else if (!quoted && depth == 0 && lower.startsWith(token, i)) {
        return i;
      }
    }

    return -1;
  }

  private static int lastTopLevel(final String text, final String token) {
    var last = -1;
    var from = 0;

    while (true) {
      final var at = topLevel(text.substring(from), token);

      if (at < 0) {
        return last;
      }

      last = from + at;
      from = last + 1;
    }
  }

  private static List<String> splitTopLevel(final String select) {
    final var items = new ArrayList<String>();
    var depth = 0;
    var start = 0;

    for (int i = 0; i < select.length(); i++) {
      final var c = select.charAt(i);

      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (c == ',' && depth == 0) {
        items.add(select.substring(start, i).trim());
        start = i + 1;
      }
    }

    items.add(select.substring(start).trim());

    return items;
  }

  private static String decapitalize(final String name) {
    return Character.toLowerCase(name.charAt(0)) + name.substring(1);
  }

  private static String simple(final Class<?> type) {
    return type.getSimpleName();
  }

  private static String unquote(final String identifier) {
    final var last = identifier.substring(identifier.lastIndexOf('.') + 1);

    return last.replace("\"", "").replace("`", "");
  }
}
