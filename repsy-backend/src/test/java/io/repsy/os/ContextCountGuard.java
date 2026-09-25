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

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.BootstrapUtils;

/**
 * Fails an integration test class that takes the number of distinct Spring contexts of the run past
 * {@value #MAX_CONTEXTS} (RPS-1353).
 *
 * <p>Every context of a run keeps its own connection pool to the one PostgreSQL container that all
 * {@link AbstractIntegrationTest} classes share (see the Hikari settings there), and Spring's own
 * context cache holds 32 contexts at most, so a run with more distinct configurations than that
 * also boots contexts again after the cache evicted them. A class that adds one more context with a
 * {@code @MockitoBean}, a {@code @DynamicPropertySource} of its own or an {@code @Import} pays for
 * it in every full run, and nothing else would tell. The count is of distinct context
 * configurations (what the Spring cache keys on), not of context instances, so it does not depend
 * on the order the classes run in or on the cache evicting anything; the class that goes over the
 * limit is the one that fails, and a partial run never comes near it.
 *
 * <p>When it fails, prefer sharing a context (the same {@code @Import}s, properties and mock beans
 * as an existing class) over raising the limit; raise {@link #MAX_CONTEXTS} only for a context that
 * cannot be shared, together with what the connection budget of the container allows.
 */
public final class ContextCountGuard implements AfterAllCallback {

  /**
   * Distinct context configurations a run may use; a full run of the classes that extend the base
   * used 33 when this was added.
   */
  static final int MAX_CONTEXTS = 38;

  private static final Logger LOG = LoggerFactory.getLogger(ContextCountGuard.class);

  private static final Set<Object> CONFIGURATIONS = ConcurrentHashMap.newKeySet();

  @Override
  public void afterAll(final ExtensionContext context) {
    final var testClass = context.getRequiredTestClass();
    final var configuration =
        BootstrapUtils.resolveTestContextBootstrapper(testClass).buildMergedContextConfiguration();

    final var known = CONFIGURATIONS.size();
    final var overLimit = record(CONFIGURATIONS, configuration, MAX_CONTEXTS);

    if (CONFIGURATIONS.size() > known) {
      LOG.info(
          "Spring context configuration {} of the run (limit {}) first used by {}",
          CONFIGURATIONS.size(),
          MAX_CONTEXTS,
          testClass.getName());
    }

    if (overLimit != null) {
      throw new AssertionFailedError(overLimit.formatted(testClass.getName()));
    }
  }

  /**
   * Adds {@code configuration} to {@code seen} and returns a message template (its {@code %s} is
   * the class) when that makes {@code seen} larger than {@code max}, or {@code null} while it fits.
   */
  static String record(final Collection<Object> seen, final Object configuration, final int max) {

    if (seen.contains(configuration)) {
      return null;
    }

    seen.add(configuration);

    if (seen.size() <= max) {
      return null;
    }

    return "%s adds Spring context configuration number "
        + seen.size()
        + ", over the limit of "
        + max
        + " that all integration tests share one PostgreSQL container for. Reuse the context of"
        + " an existing class (same @Import, @DynamicPropertySource and mock beans) instead of"
        + " adding one.";
  }
}
