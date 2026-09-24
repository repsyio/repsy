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
package io.repsy.os.retry;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;

/**
 * RPS-1322: a retry annotation does nothing until something enables it, and Spring Boot 4 enables
 * neither of them on its own, so an annotated method that nothing enables is a retry that never
 * runs and a comment that lies. The two {@code @Retryable} annotations in this code base ({@code
 * ManifestTxService}, {@code TrivyVulnerabilityScanner}) were exactly that: spring-retry's needs
 * {@code @EnableRetry}, Spring Framework 7's needs {@code @EnableResilientMethods}, and neither was
 * there.
 *
 * <p>This test reads the compiled classes, so it needs no Spring context and no Docker. Add the
 * enabling annotation with the first use of a retry annotation and this test passes again.
 */
@DisplayName("Retry annotations (RPS-1322)")
class RetryAnnotationsAreEnabledTest {

  private static final String SPRING_RETRY = "org.springframework.retry.annotation.Retryable";
  private static final String FRAMEWORK_RETRY =
      "org.springframework.resilience.annotation.Retryable";
  private static final String FRAMEWORK_CONCURRENCY =
      "org.springframework.resilience.annotation.ConcurrencyLimit";

  /** The annotation that makes a retry annotation work, by the annotation it enables. */
  private static final Map<String, String> ENABLED_BY =
      Map.of(
          SPRING_RETRY, "org.springframework.retry.annotation.EnableRetry",
          FRAMEWORK_RETRY, "org.springframework.resilience.annotation.EnableResilientMethods",
          FRAMEWORK_CONCURRENCY,
              "org.springframework.resilience.annotation.EnableResilientMethods");

  @Test
  @DisplayName("every class that uses a retry annotation has the annotation that enables it")
  void noRetryAnnotationWithoutItsEnabler() throws IOException {
    final var resolver = new PathMatchingResourcePatternResolver();
    final var readers = new SimpleMetadataReaderFactory(resolver);
    final var resources = resolver.getResources("classpath*:io/repsy/**/*.class");

    final var usesByAnnotation = new TreeMap<String, List<String>>();
    final var enablers = new HashSet<String>();

    for (final var resource : resources) {
      final var metadata = readers.getMetadataReader(resource).getAnnotationMetadata();

      for (final var entry : ENABLED_BY.entrySet()) {
        if (metadata.hasAnnotation(entry.getKey())
            || metadata.hasAnnotatedMethods(entry.getKey())) {
          usesByAnnotation
              .computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
              .add(metadata.getClassName());
        }

        if (metadata.hasAnnotation(entry.getValue())) {
          enablers.add(entry.getValue());
        }
      }
    }

    usesByAnnotation.keySet().removeIf(annotation -> enablers.contains(ENABLED_BY.get(annotation)));

    assertThat(resources.length).as("the scan found the application's classes").isGreaterThan(500);
    assertThat(usesByAnnotation)
        .as(
            "a retry annotation is dead code unless @EnableRetry (spring-retry) or"
                + " @EnableResilientMethods (Spring Framework 7) is on a configuration class")
        .isEmpty();
  }
}
