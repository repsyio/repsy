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

import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class PagingParameterConfiguration implements WebMvcConfigurer {

  @Override
  public void addInterceptors(final @NonNull InterceptorRegistry registry) {
    // Last, so authentication and authorisation still answer 401/403 before a bad page or size
    // is reported.
    registry
        .addInterceptor(new PagingParameterInterceptor())
        .addPathPatterns("/**")
        .order(Ordered.LOWEST_PRECEDENCE);
  }
}
