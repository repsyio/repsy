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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * RPS-1049: the limits can be set with the {@code MULTIPART_MAX_FILE_SIZE} and {@code
 * MULTIPART_MAX_REQUEST_SIZE} variables, and an upload over them is answered with 413.
 *
 * <p>The limits are set low so the upload that exceeds them stays small: Tomcat only swallows a
 * couple of megabytes of a rejected body, and a client still sending after that sees a reset
 * connection instead of the response.
 */
@TestPropertySource(
    properties = {
      "MULTIPART_MAX_FILE_SIZE=256KB",
      "MULTIPART_MAX_REQUEST_SIZE=300KB",
    })
@DisplayName("Multipart upload limits, configured (RPS-1049)")
class ConfiguredMultipartLimitsIT extends AbstractMultipartLimitIT {

  private static final int PROTOCOL_PORT = freePort();
  private static final int API_PORT_OF_CONTEXT = freePort();

  @DynamicPropertySource
  static void registerPortsOfThisContext(final DynamicPropertyRegistry registry) {
    registerPorts(registry, PROTOCOL_PORT, API_PORT_OF_CONTEXT);
  }

  @Test
  @DisplayName("accepts a chart under the configured limits")
  void acceptsAPackageUnderTheLimits() {
    assertThat(this.pushChart(PROTOCOL_PORT, 100 * 1024)).isEqualTo(201);
  }

  @Test
  @DisplayName("answers 413 to a chart over the configured limits")
  void rejectsAPackageOverTheLimits() {
    assertThat(this.pushChart(PROTOCOL_PORT, 700 * 1024)).isEqualTo(413);
  }
}
