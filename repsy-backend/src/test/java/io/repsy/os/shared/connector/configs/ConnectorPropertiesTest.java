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
package io.repsy.os.shared.connector.configs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("ConnectorProperties")
class ConnectorPropertiesTest {

  private static final String GATEWAY = "https://mcp.repsy.io/oauth/upstream/callback";

  @Test
  @DisplayName("is off with no redirect when nothing is set")
  void isOffByDefault() {
    final var properties = new ConnectorProperties(false, null);

    assertThat(properties.enabled()).isFalse();
    assertThat(properties.registeredRedirectUris()).isEmpty();
    assertThat(properties.isRegistered(GATEWAY)).isFalse();
  }

  @Test
  @DisplayName("matches a registered redirect exactly, and drops blank entries")
  void matchesExactly() {
    final var properties =
        new ConnectorProperties(true, List.of(" " + GATEWAY + " ", "", "http://127.0.0.1:8765/cb"));

    assertThat(properties.registeredRedirectUris())
        .containsExactly(GATEWAY, "http://127.0.0.1:8765/cb");
    assertThat(properties.isRegistered(GATEWAY)).isTrue();
    assertThat(properties.isRegistered(GATEWAY + "/")).isFalse();
    assertThat(properties.isRegistered(GATEWAY + "?x=1")).isFalse();
    assertThat(properties.isRegistered("HTTPS://mcp.repsy.io/oauth/upstream/callback")).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://mcp.repsy.io/callback",
        "https://mcp.repsy.io/callback#fragment",
        "/relative/callback",
        "javascript:alert(1)",
        "https://",
        "https://mcp.repsy.io/a b"
      })
  @DisplayName("refuses a redirect that is not https, has a fragment or is not absolute")
  void refusesAnUnsafeRedirect(final String redirectUri) {
    final var uris = List.of(redirectUri);

    assertThatThrownBy(() -> new ConnectorProperties(true, uris))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("repsy.connector.redirect-uris");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"http://localhost:9000/cb", "http://127.0.0.1/cb", "http://[::1]:1234/cb"})
  @DisplayName("allows plain http on a loopback host")
  void allowsLoopbackHttp(final String redirectUri) {
    assertThat(new ConnectorProperties(true, List.of(redirectUri)).isRegistered(redirectUri))
        .isTrue();
  }
}
