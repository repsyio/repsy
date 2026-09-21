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
package io.repsy.os.server.protocols.shared.configs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

@DisplayName("StorageTrashProperties")
class StorageTrashPropertiesTest {

  private static final String PREFIX = "os.app.storage.file-system";

  @Configuration
  @EnableConfigurationProperties(StorageTrashProperties.class)
  static class PropertiesConfiguration {}

  private static StorageTrashProperties bind(final Map<String, Object> properties) {
    return new Binder(new MapConfigurationPropertySource(properties))
        .bindOrCreate(PREFIX, StorageTrashProperties.class);
  }

  @Test
  @DisplayName("the retention is seven days when nothing is configured")
  void defaultsToSevenDays() {
    assertThat(bind(Map.of()).trashRetention()).isEqualTo(Duration.ofDays(7));
  }

  @Test
  @DisplayName("an ISO-8601 duration of one day or more is accepted")
  void acceptsOneDayOrMore() {
    assertThat(bind(Map.of(PREFIX + ".trash-retention", "P1D")).trashRetention())
        .isEqualTo(Duration.ofDays(1));
    assertThat(bind(Map.of(PREFIX + ".trash-retention", "P30D")).trashRetention())
        .isEqualTo(Duration.ofDays(30));
  }

  @Test
  @DisplayName("a retention below one day stops the binding with a message that names the property")
  void rejectsLessThanOneDay() {
    assertThatThrownBy(() -> bind(Map.of(PREFIX + ".trash-retention", "PT23H")))
        .isInstanceOf(BindException.class)
        .hasRootCauseInstanceOf(IllegalArgumentException.class)
        .rootCause()
        .hasMessageContaining("trash-retention")
        .hasMessageContaining("TRASH_RETENTION")
        .hasMessageContaining("PT23H");
  }

  @Test
  @DisplayName("a zero or negative retention is rejected, as it would delete today's trash")
  void rejectsZeroAndNegative() {
    assertThatThrownBy(() -> new StorageTrashProperties(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StorageTrashProperties(Duration.ofDays(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("an application context does not start with a retention below one day")
  void contextFailsToStartWithLessThanOneDay() {
    new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration.class)
        .withPropertyValues(PREFIX + ".trash-retention=PT1H")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("must be at least P1D"));
  }

  @Test
  @DisplayName("an application context starts with the default retention")
  void contextStartsWithTheDefault() {
    new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration.class)
        .run(
            context ->
                assertThat(context.getBean(StorageTrashProperties.class).trashRetention())
                    .isEqualTo(Duration.ofDays(7)));
  }
}
