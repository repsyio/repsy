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
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Allows the panel API to be called cross-origin, and only the panel API (RPS-1514). See {@link
 * AppCorsProperties} for how {@code app.allowed-origins} (env {@code APP_ALLOWED_ORIGINS})
 * restricts this; unset, it keeps today's behaviour of accepting any origin.
 *
 * <p>The repository-serving port sends no CORS header at all: no browser calls it cross-origin (the
 * panel's CSP {@code connect-src} names only its own origin and {@code app.allowed-origins}, and
 * the package-manager clients never send an {@code Origin}), so answering a preflight there served
 * nobody. A request there passes straight to the protocol router, which keeps answering {@code
 * OPTIONS} the way it always did. That is why this is a servlet filter and not a {@code
 * WebMvcConfigurer} mapping: a mapping is global to the {@code DispatcherServlet} and cannot see
 * which port a request arrived on.
 */
@Component
public class CorsGlobalConfiguration extends OncePerRequestFilter {

  private final @NonNull ApiPortMatcher apiPortMatcher;
  private final @NonNull CorsFilter apiCorsFilter;

  public CorsGlobalConfiguration(
      final @NonNull AppCorsProperties appCorsProperties,
      final @NonNull ApiPortMatcher apiPortMatcher) {

    this.apiPortMatcher = apiPortMatcher;

    final var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", buildConfiguration(appCorsProperties));
    this.apiCorsFilter = new CorsFilter(source);
  }

  @Override
  protected void doFilterInternal(
      final @NonNull HttpServletRequest request,
      final @NonNull HttpServletResponse response,
      final @NonNull FilterChain filterChain)
      throws ServletException, IOException {

    if (!this.apiPortMatcher.isApiPort(request.getLocalPort())) {
      filterChain.doFilter(request, response);
      return;
    }

    this.apiCorsFilter.doFilter(request, response, filterChain);
  }

  private static @NonNull CorsConfiguration buildConfiguration(
      final @NonNull AppCorsProperties appCorsProperties) {

    final var configuration = new CorsConfiguration();
    configuration.addAllowedMethod("*");
    configuration.addAllowedHeader("*");
    configuration.setAllowCredentials(true);

    final var allowedOrigins = appCorsProperties.allowedOriginList();

    if (allowedOrigins.isEmpty()) {
      // No app.allowed-origins configured: keep today's behaviour. allowedOriginPatterns("*")
      // (unlike allowedOrigins("*")) is allowed together with allowCredentials(true), since Spring
      // reflects the request's actual Origin back instead of a literal "*".
      configuration.addAllowedOriginPattern("*");
    } else {
      // allowCredentials(true) requires exact origins, not patterns, once the list is configured.
      allowedOrigins.forEach(configuration::addAllowedOrigin);
    }

    return configuration;
  }
}
