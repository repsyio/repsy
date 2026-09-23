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

import io.repsy.libs.multiport.configs.props.MultiPortProperties;
import io.repsy.os.shared.utils.MultiPortNames;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sends a {@code Content-Security-Policy} (or, in report-only mode, {@code
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
 * <p>The built-in policy allows what the panel's {@code index.html} actually loads: Google Tag
 * Manager/gtag ({@code www.googletagmanager.com}, plus {@code www.google-analytics.com} for the
 * collect/beacon calls gtag.js makes once it loads) and cdnjs.cloudflare.com (Font Awesome CSS and
 * webfonts). {@code connect-src} additionally allows whatever origins {@link AppCorsProperties}
 * allows, since a browser calling the API cross-origin from one of those origins is exactly what
 * CORS was configured to allow. An operator can widen or replace the policy via {@link
 * ContentSecurityPolicyProperties#policy()} without a rebuild.
 */
@Component
@RequiredArgsConstructor
public class SecurityHeadersFilter extends OncePerRequestFilter {

  private static final @NonNull String API_PATH_PREFIX = "/api/";
  private static final @NonNull String ENFORCED_HEADER = "Content-Security-Policy";
  private static final @NonNull String REPORT_ONLY_HEADER = "Content-Security-Policy-Report-Only";

  private final @NonNull MultiPortProperties multiPortProperties;
  private final @NonNull AppCorsProperties appCorsProperties;
  private final @NonNull ContentSecurityPolicyProperties cspProperties;

  @Override
  protected void doFilterInternal(
      final @NonNull HttpServletRequest request,
      final @NonNull HttpServletResponse response,
      final @NonNull FilterChain filterChain)
      throws ServletException, IOException {

    if (this.cspProperties.enabled() && this.isSpaOrStaticRequest(request)) {
      final var headerName = this.cspProperties.reportOnly() ? REPORT_ONLY_HEADER : ENFORCED_HEADER;
      response.setHeader(headerName, this.resolvePolicy());
    }

    filterChain.doFilter(request, response);
  }

  private boolean isSpaOrStaticRequest(final @NonNull HttpServletRequest request) {

    return this.isApiPort(request.getLocalPort())
        && !request.getRequestURI().startsWith(API_PATH_PREFIX);
  }

  private boolean isApiPort(final int localPort) {

    if (localPort == this.multiPortProperties.getPortFor(MultiPortNames.PORT_API)) {
      return true;
    }

    return MultiPortNames.PORT_API.equals(this.multiPortProperties.getPortAliases().get(localPort));
  }

  private @NonNull String resolvePolicy() {

    final var override = this.cspProperties.policy();

    if (override != null && !override.isBlank()) {
      return override;
    }

    return this.buildDefaultPolicy();
  }

  private @NonNull String buildDefaultPolicy() {

    final var connectSrc =
        new StringBuilder(
            "connect-src 'self' https://www.googletagmanager.com https://www.google-analytics.com");

    for (final var origin : this.appCorsProperties.allowedOriginList()) {
      connectSrc.append(' ').append(origin);
    }

    return String.join(
        "; ",
        List.of(
            "default-src 'self'",
            "script-src 'self' https://www.googletagmanager.com",
            "style-src 'self' 'unsafe-inline' https://cdnjs.cloudflare.com",
            "font-src 'self' https://cdnjs.cloudflare.com data:",
            "img-src 'self' data: https://www.googletagmanager.com"
                + " https://www.google-analytics.com",
            connectSrc.toString(),
            "object-src 'none'",
            "base-uri 'self'",
            "frame-ancestors 'none'",
            "form-action 'self'"));
  }
}
