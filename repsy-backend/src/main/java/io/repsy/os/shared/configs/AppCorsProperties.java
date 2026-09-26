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

import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Origins the panel API accepts cross-origin requests from (see {@code CorsGlobalConfiguration}).
 *
 * <p>Unset (the default) means same-origin only: the panel API sends no CORS header, so a browser
 * blocks a page on another origin from reading its responses (RPS-1590). The Docker image serves
 * the SPA and the API from one origin ({@code API_BASE_URL} empty), which needs no CORS. A split
 * setup (the Angular dev server on :4200 against the API on :8080, or a SPA hosted elsewhere) must
 * name the SPA's origin here, for example {@code APP_ALLOWED_ORIGINS=http://localhost:4200}.
 *
 * <p>Once set, exactly the listed origins are allowed, with credentials, because Spring only allows
 * exact origins (not patterns) together with {@code allowCredentials(true)}.
 *
 * <p>Bound as a single, comma-separated string (rather than a {@code List<String>}) so an unset
 * {@code APP_ALLOWED_ORIGINS} resolves to an empty string and therefore an empty list; a
 * property-source list binding would instead have bound the empty string to a one-element list
 * holding an empty origin, silently opening CORS to a blank origin.
 *
 * @param allowedOrigins comma-separated exact origins (for example {@code
 *     https://panel.example.com,https://panel-staging.example.com}), empty unless {@code
 *     APP_ALLOWED_ORIGINS} is set
 */
@ConfigurationProperties(prefix = "app")
public record AppCorsProperties(@Nullable String allowedOrigins) {

  /**
   * @return {@link #allowedOrigins()} split on commas, trimmed, with blank entries dropped; empty
   *     when {@link #allowedOrigins()} is {@code null} or blank
   */
  public @NonNull List<String> allowedOriginList() {

    if (this.allowedOrigins == null || this.allowedOrigins.isBlank()) {
      return List.of();
    }

    return Arrays.stream(this.allowedOrigins.split(","))
        .map(String::trim)
        .filter(origin -> !origin.isEmpty())
        .toList();
  }
}
