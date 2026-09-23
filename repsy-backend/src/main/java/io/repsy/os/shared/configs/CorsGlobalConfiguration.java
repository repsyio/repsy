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

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Allows the panel API to be called cross-origin. See {@link AppCorsProperties} for how {@code
 * app.allowed-origins} (env {@code APP_ALLOWED_ORIGINS}) restricts this; unset, it keeps today's
 * behaviour of accepting any origin.
 */
@Configuration
@RequiredArgsConstructor
public class CorsGlobalConfiguration implements WebMvcConfigurer {

  private final @NonNull AppCorsProperties appCorsProperties;

  @Override
  public void addCorsMappings(final @NonNull CorsRegistry registry) {

    final var mapping =
        registry.addMapping("/**").allowedMethods("*").allowedHeaders("*").allowCredentials(true);

    final var allowedOrigins = this.appCorsProperties.allowedOriginList();

    if (allowedOrigins.isEmpty()) {
      // No app.allowed-origins configured: keep today's behaviour. allowedOriginPatterns("*")
      // (unlike allowedOrigins("*")) is allowed together with allowCredentials(true), since Spring
      // reflects the request's actual Origin back instead of a literal "*".
      mapping.allowedOriginPatterns("*");
    } else {
      // allowCredentials(true) requires exact origins, not patterns, once the list is configured.
      mapping.allowedOrigins(allowedOrigins.toArray(new String[0]));
    }
  }
}
