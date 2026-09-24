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

import io.repsy.protocols.shared.repo.dtos.RepoType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Makes every {@link RepoType} request parameter and path variable of the panel API
 * case-insensitive (RPS-1269): {@code ?type=maven} is {@code ?type=MAVEN}. The canonical spelling
 * (what the API returns) is the upper-case name; the lower-case slug is accepted on input only. The
 * converter is registered for {@code RepoType} alone, so other enums keep Spring's exact-name
 * matching. A value that names no type fails the conversion, which answers 400 {@code
 * validationError} with the parameter name as the data, like any other bad parameter. JSON bodies
 * are read by {@link RepoType#fromJson(String)}.
 */
@Configuration
public class RepoTypeConversionConfiguration implements WebMvcConfigurer {

  @Override
  public void addFormatters(final @NonNull FormatterRegistry registry) {
    registry.addConverter(String.class, RepoType.class, new StringToRepoTypeConverter());
  }

  /** {@code null} for a blank value (the parameter is absent), the type otherwise. */
  static final class StringToRepoTypeConverter implements Converter<String, RepoType> {

    @Override
    public @Nullable RepoType convert(final @NonNull String source) {
      if (source.isBlank()) {
        return null;
      }

      return RepoType.fromString(source)
          .orElseThrow(() -> new IllegalArgumentException("Unknown repo type: " + source));
    }
  }
}
