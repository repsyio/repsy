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
package io.repsy.scanner.trivy.listeners;

import io.repsy.scanner.trivy.services.TrivyScanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

// ApplicationRunner beans run before ApplicationReadyEvent and before Spring Boot's own automatic
// AvailabilityChangeEvent(ReadinessState.ACCEPTING_TRAFFIC) publish, so blocking here genuinely
// holds off readiness — unlike an @EventListener(ApplicationReadyEvent), whose ordering relative
// to that automatic publish isn't guaranteed.
@Slf4j
@Component
@RequiredArgsConstructor
public class TrivyDatabaseWarmupRunner implements ApplicationRunner {

  private final @NonNull TrivyScanService trivyScanService;
  private final @NonNull ApplicationContext applicationContext;

  @Override
  public void run(final @NonNull ApplicationArguments args) {
    this.publishReadiness(ReadinessState.REFUSING_TRAFFIC);

    try {
      log.info("Warming up Trivy vulnerability databases before accepting traffic");
      this.trivyScanService.warmUpDatabases();
      log.info("Trivy database warm-up completed");
    } catch (final Exception exception) {
      log.error(
          "Trivy database warm-up failed after exhausting retries; starting anyway — the first"
              + " real scan will retry the download",
          exception);
    } finally {
      this.publishReadiness(ReadinessState.ACCEPTING_TRAFFIC);
    }
  }

  private void publishReadiness(final @NonNull ReadinessState state) {
    AvailabilityChangeEvent.publish(this.applicationContext, state);
  }
}
