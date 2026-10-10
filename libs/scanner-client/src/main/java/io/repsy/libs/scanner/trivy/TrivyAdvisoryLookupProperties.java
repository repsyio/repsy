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
package io.repsy.libs.scanner.trivy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How the npm audit lookup (RPS-1612) shares the scanner with the scans. The lookup runs on the
 * thread of the audit request, so this bounds what a burst of audits can ask of the scanner (which
 * itself serves {@code SCANNER_ADVISORY_CONCURRENCY} lookups at once and answers 503 to the rest).
 *
 * @param maxConcurrency lookups in flight at once; another audit waits for one, up to {@code
 *     maxWaitMillis}, and then goes without a lookup
 * @param maxWaitMillis how long an audit waits for a place, in milliseconds
 */
@ConfigurationProperties(prefix = "repsy.security.trivy.advisory-lookup")
public record TrivyAdvisoryLookupProperties(
    @DefaultValue("2") int maxConcurrency, @DefaultValue("2000") long maxWaitMillis) {

  public TrivyAdvisoryLookupProperties {
    if (maxConcurrency < 1) {
      throw new IllegalArgumentException(
          "repsy.security.trivy.advisory-lookup.max-concurrency must be at least 1, but was "
              + maxConcurrency);
    }

    if (maxWaitMillis < 0) {
      throw new IllegalArgumentException(
          "repsy.security.trivy.advisory-lookup.max-wait-millis must not be negative, but was "
              + maxWaitMillis);
    }
  }
}
