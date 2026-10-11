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

import java.net.URI;
import org.jspecify.annotations.Nullable;

/**
 * Recovers the port of a public URL Repsy builds from the incoming request when a reverse proxy
 * sends only {@code X-Forwarded-Host} with an embedded port (for example {@code example.com:8443})
 * and no separate {@code X-Forwarded-Port} (RPS-1515).
 *
 * <p>{@code server.forward-headers-strategy: native} wires up Tomcat's {@code RemoteIpValve}, which
 * reads the port only from {@code X-Forwarded-Port}: a port embedded in {@code X-Forwarded-Host} is
 * parsed out and discarded there, never fed into {@code HttpServletRequest#getServerPort()}. This
 * utility re-derives it from the raw header value, which the valve leaves untouched on the request.
 */
public final class ForwardedHostUtils {

  /** The name of the header a reverse proxy names the host (and, sometimes, port) it received. */
  public static final String X_FORWARDED_HOST = "X-Forwarded-Host";

  /** The name of the header a reverse proxy names the port it received, on its own. */
  public static final String X_FORWARDED_PORT = "X-Forwarded-Port";

  private ForwardedHostUtils() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * The port to use for a public URL: {@code forwardedPort} when the proxy sent one, otherwise the
   * port embedded in {@code forwardedHost}, otherwise {@code fallbackPort} (ordinarily {@code
   * HttpServletRequest#getServerPort()}). Resolved directly from the raw header values rather than
   * trusting {@code HttpServletRequest#getServerPort()} to already reflect them, since Tomcat's
   * {@code RemoteIpValve} does not: it reads {@code X-Forwarded-Port} correctly, but parses a port
   * out of {@code X-Forwarded-Host} only to discard it.
   *
   * @param forwardedHost The raw {@code X-Forwarded-Host} header value, or {@code null} when the
   *     request carries none
   * @param forwardedPort The raw {@code X-Forwarded-Port} header value, or {@code null} when the
   *     request carries none
   * @param fallbackPort The port to use when neither header names one
   * @return The resolved port
   */
  public static int resolvePort(
      final @Nullable String forwardedHost,
      final @Nullable String forwardedPort,
      final int fallbackPort) {

    if (forwardedPort != null && !forwardedPort.isBlank()) {
      try {
        return Integer.parseInt(forwardedPort.trim());
      } catch (final NumberFormatException e) {
        // Falls through to the host-embedded or fallback port below.
      }
    }

    if (forwardedHost != null) {
      final var embeddedPort = embeddedPortOf(forwardedHost);

      if (embeddedPort != -1) {
        return embeddedPort;
      }
    }

    return fallbackPort;
  }

  /**
   * The port embedded in a {@code host} or {@code host:port} value, or {@code -1} when it carries
   * none or is not a value this can parse. A chain of proxies may send a comma-separated list (RFC
   * 7239 order); the first entry is the one the client reached, which is what a public URL should
   * reflect.
   */
  private static int embeddedPortOf(final String forwardedHost) {

    final var host = forwardedHost.split(",", 2)[0].trim();

    try {
      return URI.create("//" + host).getPort();
    } catch (final IllegalArgumentException e) {
      return -1;
    }
  }
}
