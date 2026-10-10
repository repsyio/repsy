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
package io.repsy.protocols.shared.http;

import io.repsy.protocols.shared.utils.ForwardedHostUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The public address a client reached the registry at, which the {@code Location} headers and the
 * URLs a response advertises are built from. One strategy for every protocol: the scheme and host
 * of the request (after the proxy headers Tomcat applies), and a port that also honours a port
 * embedded in {@code X-Forwarded-Host} (RPS-1515); the default port of the scheme is left out.
 */
@NullMarked
public final class PublicUrls {

  private static final int PORT_HTTP = 80;
  private static final int PORT_HTTPS = 443;

  private PublicUrls() {
    throw new UnsupportedOperationException("Utility class");
  }

  /** {@code scheme://host[:port]} of the request, without a trailing slash. */
  public static String origin(final HttpServletRequest request) {

    final var scheme = request.getScheme();
    final var host = request.getServerName();
    final var port =
        ForwardedHostUtils.resolvePort(
            request.getHeader(ForwardedHostUtils.X_FORWARDED_HOST),
            request.getHeader(ForwardedHostUtils.X_FORWARDED_PORT),
            request.getServerPort());
    final var isDefaultPort =
        ("http".equals(scheme) && port == PORT_HTTP)
            || ("https".equals(scheme) && port == PORT_HTTPS);

    return scheme + "://" + (isDefaultPort ? host : host + ":" + port);
  }

  /** {@link #origin} and the context path of the application. */
  public static String contextRoot(final HttpServletRequest request) {

    return origin(request) + request.getContextPath();
  }

  /**
   * {@link #contextRoot} of the request being handled by this thread, for code that has no request
   * in hand.
   *
   * @throws IllegalStateException When this thread is not handling a servlet request
   */
  public static String currentContextRoot() {

    if (RequestContextHolder.getRequestAttributes()
        instanceof final ServletRequestAttributes attributes) {
      return contextRoot(attributes.getRequest());
    }

    throw new IllegalStateException("No current servlet request");
  }
}
