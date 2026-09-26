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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Allows the panel API to be called cross-origin, and only the panel API (RPS-1514), and only from
 * the origins {@code app.allowed-origins} (env {@code APP_ALLOWED_ORIGINS}) names (see {@link
 * AppCorsProperties}). Unset (the default), the panel API is same-origin only: no CORS header is
 * sent at all (RPS-1590), so a browser refuses to hand a cross-origin page the response. Nothing is
 * registered in that case, not even an empty configuration, because Spring answers a cross-origin
 * request against an empty configuration with {@code 403 Invalid CORS request}, which would also
 * hit a same-origin request whose {@code Origin} a reverse proxy makes look foreign.
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
  private final @Nullable CorsFilter apiCorsFilter;

  public CorsGlobalConfiguration(
      final @NonNull AppCorsProperties appCorsProperties,
      final @NonNull ApiPortMatcher apiPortMatcher) {

    this.apiPortMatcher = apiPortMatcher;

    final var allowedOrigins = appCorsProperties.allowedOriginList();

    if (allowedOrigins.isEmpty()) {
      this.apiCorsFilter = null;
      return;
    }

    final var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", buildConfiguration(allowedOrigins));
    this.apiCorsFilter = new CorsFilter(source);
  }

  @Override
  protected void doFilterInternal(
      final @NonNull HttpServletRequest request,
      final @NonNull HttpServletResponse response,
      final @NonNull FilterChain filterChain)
      throws ServletException, IOException {

    final var corsFilter = this.apiCorsFilter;

    if (corsFilter == null || !this.apiPortMatcher.isApiPort(request.getLocalPort())) {
      filterChain.doFilter(request, response);
      return;
    }

    corsFilter.doFilter(request, response, filterChain);
  }

  private static @NonNull CorsConfiguration buildConfiguration(
      final @NonNull List<String> allowedOrigins) {

    final var configuration = new CorsConfiguration();
    configuration.addAllowedMethod("*");
    configuration.addAllowedHeader("*");
    configuration.setAllowCredentials(true);

    // allowCredentials(true) requires exact origins, not patterns.
    allowedOrigins.forEach(configuration::addAllowedOrigin);

    return configuration;
  }
}
