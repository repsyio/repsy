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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.repsy.os.nullability.fixtures.NullabilityViolations;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.repository.Repository;

/**
 * Fails when a Spring Data repository method breaks the null rules in {@link
 * RepositoryNullabilityRules} (RPS-2077): a query parameter that the query tolerates as null
 * ({@code :x is null or ...}, {@code coalesce(:x, ...)}, {@code cast(:x as ...)}) without
 * {@code @Nullable}, and a bare (non-{@code Optional}) return that is null on no result without
 * {@code @Nullable}. Under a package-level {@code @NullMarked} the first throws {@code
 * IllegalArgumentException} and the second {@code EmptyResultDataAccessException} at run time, in
 * production only when no integration test passes the null.
 *
 * <p>The check reads the signatures, so it also guards the packages that are not marked yet, and
 * the day they are marked nothing changes. {@link #FROZEN} may only shrink: a method that is fixed
 * must be deleted from it (a stale entry fails the test), and a new violation fails the test. Never
 * add an entry; annotate the parameter or return {@code Optional}.
 */
class RepositoryNullabilityTest {

  /** {@code Class#method} that predates the check, with the reason. Empty: keep it empty. */
  private static final Map<String, String> FROZEN = Map.of();

  @Test
  void everyRepositoryMethodSaysWhereNullIsLegal() {
    final var repositories = repositories();

    assertFalse(
        repositories.size() < 40,
        "expected the repository interfaces of the backend, found " + repositories.size());

    final var found = new TreeMap<String, String>();

    for (final var repository : repositories) {
      for (final var violation : RepositoryNullabilityRules.check(repository)) {
        found.put(violation.key(), violation.toString());
      }
    }

    final var unexpected = new TreeMap<>(found);
    unexpected.keySet().removeAll(FROZEN.keySet());

    assertTrue(
        unexpected.isEmpty(),
        () ->
            "repository methods that break the null rules:\n"
                + String.join("\n", unexpected.values()));

    final var stale = new TreeSet<>(FROZEN.keySet());
    stale.removeAll(found.keySet());

    assertTrue(stale.isEmpty(), () -> "fixed methods still in FROZEN, delete them: " + stale);
  }

  @Test
  void aPlantedViolationOfEachRuleIsFound() {
    final var violations =
        RepositoryNullabilityRules.check(NullabilityViolations.BadRepository.class).stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    violation -> violation.method().getName(),
                    violation -> violation,
                    (first, second) -> first));

    assertEquals(
        Set.of(
            "isNullParameter",
            "coalesceParameter",
            "castParameter",
            "positionalParameter",
            "nullableAggregate",
            "findByName",
            "bareEntityFromQuery"),
        violations.keySet());
  }

  @Test
  void aMethodThatFollowsTheRulesIsNotFound() {
    assertEquals(
        List.of(), RepositoryNullabilityRules.check(NullabilityViolations.OkRepository.class));
  }

  private static List<Class<?>> repositories() {
    final var scanner =
        new ClassPathScanningCandidateComponentProvider(false) {
          @Override
          protected boolean isCandidateComponent(final AnnotatedBeanDefinition definition) {
            return definition.getMetadata().isInterface();
          }
        };
    scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));

    final var classes = new TreeMap<String, Class<?>>();

    for (final BeanDefinition definition : scanner.findCandidateComponents("io.repsy")) {
      final var name = definition.getBeanClassName();

      if (name == null || name.startsWith("io.repsy.os.nullability.fixtures.")) {
        continue;
      }

      try {
        classes.put(name, Class.forName(name));
      } catch (final ClassNotFoundException e) {
        throw new IllegalStateException(e);
      }
    }

    return List.copyOf(classes.values());
  }
}
