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
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class CorsGlobalConfiguration implements WebMvcConfigurer {
  private final @NonNull AppProperties appProperties;
  private final @NonNull Environment environment;

  @Override
  public void addCorsMappings(final @NonNull CorsRegistry registry) {
    if (Arrays.asList(this.environment.getActiveProfiles()).contains("local")) {
      registry
          .addMapping("/**")
          .allowedOriginPatterns("*")
          .allowedMethods("*")
          .allowedHeaders("*")
          .allowCredentials(true);
    } else {
      registry
          .addMapping("/**")
          .allowedOrigins(this.appProperties.getAllowedOrigins().toArray(new String[0]))
          .allowedMethods("*")
          .allowedHeaders("*")
          .allowCredentials(true);
    }
  }
}
