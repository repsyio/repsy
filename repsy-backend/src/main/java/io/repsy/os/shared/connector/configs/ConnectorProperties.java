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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the consent flow of connected applications (RPS-2660), such as the remote MCP gateway
 * of Repsy Cloud. Off by default: Repsy OS has no use for it, and the routes answer 404 until an
 * operator turns it on.
 *
 * @param enabled whether the consent routes are served at all
 * @param redirectUris the registered redirects of the applications that may ask for a consent,
 *     compared as exact strings; each is an absolute {@code https} URI without a fragment, or a
 *     plain {@code http} one on a loopback host (for tests)
 */
@ConfigurationProperties(prefix = "repsy.connector")
public record ConnectorProperties(boolean enabled, @Nullable List<String> redirectUris) {

  private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

  public ConnectorProperties {
    redirectUris =
        redirectUris == null
            ? List.of()
            : redirectUris.stream().map(String::trim).filter(uri -> !uri.isEmpty()).toList();

    redirectUris.forEach(ConnectorProperties::checkRedirectUri);
  }

  /** The registered redirects, never {@code null}. */
  public List<String> registeredRedirectUris() {
    return redirectUris == null ? List.of() : redirectUris;
  }

  /** Whether {@code redirectUri} is registered, by exact string comparison. */
  public boolean isRegistered(final String redirectUri) {
    return this.registeredRedirectUris().contains(redirectUri);
  }

  private static void checkRedirectUri(final String redirectUri) {
    final URI uri;

    try {
      uri = new URI(redirectUri);
    } catch (final URISyntaxException e) {
      throw invalid(redirectUri, e);
    }

    final var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    final var host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    final var secure = "https".equals(scheme);
    final var loopback = "http".equals(scheme) && LOOPBACK_HOSTS.contains(host);

    if (host.isEmpty() || uri.getRawFragment() != null || !(secure || loopback)) {
      throw invalid(redirectUri, null);
    }
  }

  private static IllegalArgumentException invalid(
      final String redirectUri, final @Nullable Exception cause) {
    return new IllegalArgumentException(
        "repsy.connector.redirect-uris: "
            + redirectUri
            + " is not an absolute https URI without a fragment (plain http only on a loopback"
            + " host)",
        cause);
  }
}
