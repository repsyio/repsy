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
package io.repsy.os.server.shared.multipart;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * RPS-1049: with no configuration, a package well over Spring Boot's multipart defaults (1 MB per
 * file, 10 MB per request) is accepted by a real Tomcat.
 */
@DisplayName("Multipart upload limits, default configuration (RPS-1049)")
class DefaultMultipartLimitsIT extends AbstractMultipartLimitIT {

  private static final int PROTOCOL_PORT = freePort();
  private static final int API_PORT_OF_CONTEXT = freePort();

  /** Well over the 10 MB request default, well under the configured default. */
  private static final int ARCHIVE_BYTES = 12 * 1024 * 1024;

  @Value("${spring.servlet.multipart.max-file-size:unset}")
  private String maxFileSize;

  @Value("${spring.servlet.multipart.max-request-size:unset}")
  private String maxRequestSize;

  @DynamicPropertySource
  static void registerPortsOfThisContext(final DynamicPropertyRegistry registry) {
    registerPorts(registry, PROTOCOL_PORT, API_PORT_OF_CONTEXT);
  }

  @Test
  @DisplayName("accepts a 12 MB chart, which the Spring Boot defaults rejected")
  void acceptsAPackageOverTheSpringBootDefaults() {
    assertThat(this.pushChart(PROTOCOL_PORT, ARCHIVE_BYTES)).isEqualTo(201);
  }

  @Test
  @DisplayName("sets the limits to 500 MB instead of leaving the Spring Boot defaults")
  void setsExplicitLimits() {
    assertThat(this.maxFileSize).isEqualTo("500MB");
    assertThat(this.maxRequestSize).isEqualTo("500MB");
  }
}
