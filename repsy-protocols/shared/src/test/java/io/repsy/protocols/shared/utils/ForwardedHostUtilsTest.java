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
package io.repsy.protocols.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ForwardedHostUtils")
class ForwardedHostUtilsTest {

  /** RPS-1515: RemoteIpValve parses the port out of X-Forwarded-Host and discards it. */
  @Test
  @DisplayName("recovers the port embedded in X-Forwarded-Host when X-Forwarded-Port is absent")
  void recoversEmbeddedPortWhenNoSeparatePortHeader() {
    assertThat(ForwardedHostUtils.resolvePort("pub.e2e.test:8443", null, 443)).isEqualTo(8443);
  }

  @Test
  @DisplayName("prefers a separate X-Forwarded-Port over the one embedded in the host")
  void prefersSeparatePortHeader() {
    assertThat(ForwardedHostUtils.resolvePort("pub.e2e.test:8443", "9443", 443)).isEqualTo(9443);
  }

  @Test
  @DisplayName("uses the separate X-Forwarded-Port when the host carries none")
  void usesSeparatePortHeaderWithBareHost() {
    assertThat(ForwardedHostUtils.resolvePort("pub.e2e.test", "8443", 443)).isEqualTo(8443);
  }

  @Test
  @DisplayName("falls back to the given port when neither header names one")
  void fallsBackWithoutAnyForwardedPort() {
    assertThat(ForwardedHostUtils.resolvePort("pub.e2e.test", null, 443)).isEqualTo(443);
    assertThat(ForwardedHostUtils.resolvePort(null, null, 9090)).isEqualTo(9090);
  }

  @Test
  @DisplayName("falls back when X-Forwarded-Port is blank")
  void fallsBackWhenPortHeaderBlank() {
    assertThat(ForwardedHostUtils.resolvePort("pub.e2e.test:8443", " ", 443)).isEqualTo(8443);
  }

  @Test
  @DisplayName("takes the first host of a proxy chain's comma-separated list")
  void takesFirstHostOfChain() {
    assertThat(ForwardedHostUtils.resolvePort("pub.e2e.test:8443, internal:9090", null, 443))
        .isEqualTo(8443);
  }

  @Test
  @DisplayName("falls back when the embedded port cannot be parsed")
  void fallsBackForUnparsablePort() {
    assertThat(ForwardedHostUtils.resolvePort("pub.e2e.test:notaport", null, 443)).isEqualTo(443);
  }

  @Test
  @DisplayName("falls back to the host-embedded port when X-Forwarded-Port is unparsable")
  void fallsBackToEmbeddedPortForUnparsablePortHeader() {
    assertThat(ForwardedHostUtils.resolvePort("pub.e2e.test:8443", "notaport", 443))
        .isEqualTo(8443);
  }
}
