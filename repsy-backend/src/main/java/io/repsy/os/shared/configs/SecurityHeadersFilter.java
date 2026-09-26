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
package io.repsy.os.shared.configs;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sends the browser-facing security headers (RPS-1514): {@code X-Content-Type-Options: nosniff} on
 * every response of both ports; on the API port also {@code Referrer-Policy:
 * strict-origin-when-cross-origin} and {@code X-Frame-Options: DENY} (the legacy twin of the CSP's
 * {@code frame-ancestors 'none'}) on every path, {@code /api/**} included; and, only when {@code
 * app.hsts-max-age} is set and the request is secure, {@code Strict-Transport-Security} (see {@link
 * AppHstsProperties} for why that is opt-in).
 *
 * <p>It also sends a {@code Content-Security-Policy} (or, in report-only mode, {@code
 * Content-Security-Policy-Report-Only}) header with the panel SPA and its static assets, so a
 * sanitiser bypass in the README viewer (see {@code MarkdownComponent} in the frontend) or any
 * future {@code [innerHTML]} use has a second barrier: the browser itself refuses to load or run
 * remote content the policy does not allow.
 *
 * <p>Repsy OS has no Spring Security, so this is a plain servlet filter rather than a security
 * header customizer. A {@code <meta http-equiv="Content-Security-Policy">} tag was considered
 * instead of a filter, but it cannot carry {@code frame-ancestors} (the directive that blocks
 * clickjacking), so a response header is required.
 *
 * <p>The header is sent only for requests that can render the panel SPA or its static assets: on
 * the API port (see {@code MultiPortNames#PORT_API}), for any path other than the JSON API under
 * {@code /api/**}. That excludes the JSON API responses (a JSON body has nothing to enforce a CSP
 * against) and the repository-serving port, and includes {@code SpaController}'s {@code
 * forward:/index.html} responses and the static resources (JS/CSS bundles, {@code index.html}
 * itself) Spring Boot serves from {@code spring.web.resources.static-locations}.
 *
 * <p>The built-in policy allows only the panel's own origin (RPS-1402): the panel loads no
 * analytics, CDN or avatar service, so no third-party host is named. {@code connect-src}
 * additionally allows whatever origins {@link AppCorsProperties} allows, since a browser calling
 * the API cross-origin from one of those origins is exactly what CORS was configured to allow. An
 * operator can widen or replace the policy via {@link ContentSecurityPolicyProperties#policy()}
 * without a rebuild.
 */
// Runs before the CORS filter, so even a CORS rejection (403) carries the headers.
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@Component
@RequiredArgsConstructor
public class SecurityHeadersFilter extends OncePerRequestFilter {

  private static final @NonNull String API_PATH_PREFIX = "/api/";
  private static final @NonNull String ENFORCED_HEADER = "Content-Security-Policy";
  private static final @NonNull String REPORT_ONLY_HEADER = "Content-Security-Policy-Report-Only";
  private static final @NonNull String CONTENT_TYPE_OPTIONS_HEADER = "X-Content-Type-Options";
  private static final @NonNull String REFERRER_POLICY_HEADER = "Referrer-Policy";
  private static final @NonNull String FRAME_OPTIONS_HEADER = "X-Frame-Options";
  private static final @NonNull String HSTS_HEADER = "Strict-Transport-Security";

  private final @NonNull ApiPortMatcher apiPortMatcher;
  private final @NonNull AppCorsProperties appCorsProperties;
  private final @NonNull ContentSecurityPolicyProperties cspProperties;
  private final @NonNull AppHstsProperties hstsProperties;

  @Override
  protected void doFilterInternal(
      final @NonNull HttpServletRequest request,
      final @NonNull HttpServletResponse response,
      final @NonNull FilterChain filterChain)
      throws ServletException, IOException {

    this.setHardeningHeaders(request, response);

    if (this.cspProperties.enabled() && this.isSpaOrStaticRequest(request)) {
      final var headerName = this.cspProperties.reportOnly() ? REPORT_ONLY_HEADER : ENFORCED_HEADER;
      response.setHeader(headerName, this.resolvePolicy());
    }

    filterChain.doFilter(request, response);
  }

  private void setHardeningHeaders(
      final @NonNull HttpServletRequest request, final @NonNull HttpServletResponse response) {

    // Every response of both ports: the protocol port serves user-uploaded bytes (RPS-1514).
    response.setHeader(CONTENT_TYPE_OPTIONS_HEADER, "nosniff");

    if (this.hstsProperties.enabled() && request.isSecure()) {
      response.setHeader(HSTS_HEADER, this.hstsProperties.headerValue());
    }

    if (this.apiPortMatcher.isApiPort(request.getLocalPort())) {
      response.setHeader(REFERRER_POLICY_HEADER, "strict-origin-when-cross-origin");
      response.setHeader(FRAME_OPTIONS_HEADER, "DENY");
    }
  }

  private boolean isSpaOrStaticRequest(final @NonNull HttpServletRequest request) {

    return this.apiPortMatcher.isApiPort(request.getLocalPort())
        && !request.getRequestURI().startsWith(API_PATH_PREFIX);
  }

  private @NonNull String resolvePolicy() {

    final var override = this.cspProperties.policy();

    if (override != null && !override.isBlank()) {
      return override;
    }

    return this.buildDefaultPolicy();
  }

  private @NonNull String buildDefaultPolicy() {

    final var connectSrc = new StringBuilder("connect-src 'self'");

    for (final var origin : this.appCorsProperties.allowedOriginList()) {
      connectSrc.append(' ').append(origin);
    }

    return String.join(
        "; ",
        List.of(
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline'",
            "font-src 'self' data:",
            "img-src 'self' data:",
            connectSrc.toString(),
            "object-src 'none'",
            "base-uri 'self'",
            "frame-ancestors 'none'",
            "form-action 'self'"));
  }
}
