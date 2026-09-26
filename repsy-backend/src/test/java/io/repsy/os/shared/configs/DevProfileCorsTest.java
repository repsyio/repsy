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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

/**
 * RPS-1590: {@code mvn spring-boot:run} activates the {@code dev} profile (repsy-backend/pom.xml),
 * which must keep the split dev setup (Angular dev server on :4200, API on :8080) working now that
 * an unset {@code app.allowed-origins} is same-origin only.
 */
@DisplayName("application-dev.yml")
class DevProfileCorsTest {

  private static PropertySourcesPropertyResolver resolver() throws IOException {

    final var sources = new MutablePropertySources();
    new YamlPropertySourceLoader()
        .load("dev", new ClassPathResource("application-dev.yml"))
        .forEach(sources::addLast);

    return new PropertySourcesPropertyResolver(sources);
  }

  @Test
  @DisplayName("allows the Angular dev server origin by default")
  void allowsAngularDevServer() throws IOException {
    assertThat(resolver().getProperty("app.allowed-origins")).isEqualTo("http://localhost:4200");
  }
}
