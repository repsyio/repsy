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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Repsy OS has no metrics exporter (no Actuator), but code shared with Repsy Cloud, which has one,
 * counts events on a {@link MeterRegistry} (for example {@code repsy.auth.throttle_would_block}).
 * This in-memory registry keeps that code identical in both; a deployment that adds Actuator and an
 * exporter gets Spring Boot's registry instead.
 */
@Configuration(proxyBeanMethods = false)
public class MetricsConfig {

  @Bean
  @ConditionalOnMissingBean(MeterRegistry.class)
  public MeterRegistry meterRegistry() {

    return new SimpleMeterRegistry();
  }
}
