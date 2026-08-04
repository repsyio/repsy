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
package io.repsy.scanner.trivy.security;

import io.repsy.scanner.trivy.config.ScannerSecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

  private static final String API_KEY_HEADER = "X-Scanner-Api-Key";
  private static final String HEALTH_PATH = "/health";
  private static final String ACTUATOR_HEALTH_PATH = "/actuator/health";
  private static final String ACTUATOR_HEALTH_PATH_PREFIX = "/actuator/health/";

  private final @NonNull ScannerSecurityProperties properties;

  @Override
  protected void doFilterInternal(
      final @NonNull HttpServletRequest request,
      final @NonNull HttpServletResponse response,
      final @NonNull FilterChain filterChain)
      throws ServletException, IOException {

    if (isUnauthenticatedPath(request.getServletPath())) {
      filterChain.doFilter(request, response);
      return;
    }

    if (!this.isAuthorized(request)) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.getWriter().write("{\"message\":\"unauthorized\"}");
      return;
    }

    filterChain.doFilter(request, response);
  }

  private static boolean isUnauthenticatedPath(final @NonNull String servletPath) {
    return HEALTH_PATH.equals(servletPath)
        || ACTUATOR_HEALTH_PATH.equals(servletPath)
        || servletPath.startsWith(ACTUATOR_HEALTH_PATH_PREFIX);
  }

  private boolean isAuthorized(final @NonNull HttpServletRequest request) {
    final var providedKey = request.getHeader(API_KEY_HEADER);

    if (providedKey == null) {
      return false;
    }

    return MessageDigest.isEqual(
        providedKey.getBytes(StandardCharsets.UTF_8),
        this.properties.apiKey().getBytes(StandardCharsets.UTF_8));
  }
}
