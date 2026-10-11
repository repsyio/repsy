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

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The null rules of a Spring Data repository method (RPS-2077). Under a package-level
 * {@code @NullMarked} Spring Data enforces what the signature says: a {@code null} argument for a
 * parameter that is not {@code @Nullable} throws {@code IllegalArgumentException}, and a {@code
 * null} result of a method that is not {@code @Nullable} (or {@code Optional}) throws {@code
 * EmptyResultDataAccessException}. The rules are checked on the signature, so they hold whether or
 * not the package is marked yet.
 */
final class RepositoryNullabilityRules {

  /** {@code :x is null}, {@code :x is not null}, {@code :x = null}, {@code ?1 is null}. */
  private static final Pattern IS_NULL =
      Pattern.compile(
          "([:?])(\\w+)\\s*(?:is\\s+(?:not\\s+)?null\\b|(?:=|<>|!=)\\s*null\\b)",
          Pattern.CASE_INSENSITIVE);

  /** {@code coalesce(:x, ...)} and {@code nullif(:x, ...)}. */
  private static final Pattern COALESCE =
      Pattern.compile("\\b(?:coalesce|nullif)\\s*\\(\\s*([:?])(\\w+)", Pattern.CASE_INSENSITIVE);

  /** {@code cast(:x as type)}, the idiom that types a nullable parameter of a native query. */
  private static final Pattern CAST =
      Pattern.compile("\\bcast\\s*\\(\\s*([:?])(\\w+)\\s+as\\b", Pattern.CASE_INSENSITIVE);

  /** A query whose first selected expression is an aggregate that is null on an empty set. */
  private static final Pattern AGGREGATE =
      Pattern.compile("^\\s*select\\s+(?:sum|max|min|avg)\\s*\\(", Pattern.CASE_INSENSITIVE);

  private RepositoryNullabilityRules() {}

  /** One broken rule: the method, what is wrong, and what to do about it. */
  record Violation(Method method, String problem, String fix) {

    String key() {
      return this.method.getDeclaringClass().getName() + "#" + this.method.getName();
    }

    @Override
    public String toString() {
      return key() + ": " + this.problem + ". Fix: " + this.fix;
    }
  }

  static List<Violation> check(final Class<?> repository) {
    final var violations = new ArrayList<Violation>();

    for (final var method : repository.getDeclaredMethods()) {
      if (method.isSynthetic() || method.isBridge()) {
        continue;
      }

      violations.addAll(nullableParameters(method));
      violations.addAll(nullableReturn(method));
    }

    return violations;
  }

  private static List<Violation> nullableParameters(final Method method) {
    final var query = AnnotatedElementUtils.findMergedAnnotation(method, Query.class);

    if (query == null) {
      return List.of();
    }

    final var violations = new ArrayList<Violation>();
    final var parameters = method.getParameters();

    for (final var text : Stream.of(query.value(), query.countQuery()).toList()) {
      for (final var pattern : List.of(IS_NULL, COALESCE, CAST)) {
        final var matcher = pattern.matcher(text);

        while (matcher.find()) {
          final var parameter = parameterOf(parameters, matcher);

          if (parameter != null && !isNullable(parameter)) {
            violations.add(
                new Violation(
                    method,
                    "the query tolerates a null '"
                        + matcher.group(1)
                        + matcher.group(2)
                        + "' but parameter '"
                        + parameter.getName()
                        + "' is not @Nullable",
                    "annotate the parameter with @org.jspecify.annotations.Nullable"));
          }
        }
      }
    }

    return violations.stream().distinct().toList();
  }

  private static Parameter parameterOf(final Parameter[] parameters, final Matcher matcher) {
    final var name = matcher.group(2);

    if ("?".equals(matcher.group(1))) {
      final var index = Integer.parseInt(name) - 1;
      return index >= 0 && index < parameters.length ? parameters[index] : null;
    }

    for (final var parameter : parameters) {
      final var param = parameter.getAnnotation(Param.class);
      final var bound = param != null ? param.value() : parameter.getName();

      if (name.equals(bound)) {
        return parameter;
      }
    }

    return null;
  }

  private static List<Violation> nullableReturn(final Method method) {
    final var type = method.getReturnType();

    if (isNeverNull(type) || isNullable(method) || method.getName().startsWith("count")) {
      return List.of();
    }

    final var query = AnnotatedElementUtils.findMergedAnnotation(method, Query.class);
    final var aggregate = query != null && (AGGREGATE.matcher(query.value()).find());
    final var what =
        aggregate
            ? "the query selects an aggregate that is null on an empty set"
            : "the method returns a bare " + type.getSimpleName() + ", which is null on no result";

    return List.of(
        new Violation(
            method,
            what + " but is not @Nullable",
            "return Optional<"
                + type.getSimpleName()
                + "> or annotate the return type with"
                + " @org.jspecify.annotations.Nullable"));
  }

  /** The return types that are never {@code null}: Spring Data answers empty ones instead. */
  private static boolean isNeverNull(final Class<?> type) {
    return type.isPrimitive()
        || type == Void.class
        || type == Optional.class
        || type.isArray()
        || Iterable.class.isAssignableFrom(type)
        || Collection.class.isAssignableFrom(type)
        || Slice.class.isAssignableFrom(type)
        || Stream.class.isAssignableFrom(type)
        || java.util.Map.class.isAssignableFrom(type)
        || type == Boolean.class;
  }

  private static boolean isNullable(final Method method) {
    return hasNullable(method.getAnnotations())
        || hasNullable(method.getAnnotatedReturnType().getAnnotations());
  }

  private static boolean isNullable(final Parameter parameter) {
    return hasNullable(parameter.getAnnotations())
        || hasNullable(parameter.getAnnotatedType().getAnnotations());
  }

  private static boolean hasNullable(final Annotation[] annotations) {
    return Arrays.stream(annotations)
        .anyMatch(annotation -> "Nullable".equals(annotation.annotationType().getSimpleName()));
  }
}
