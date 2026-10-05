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
package io.repsy.os.server.protocols.docker.shared.cleanup.services;

import io.repsy.os.config.async.MaintenanceTaskExecutorConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Looks for Docker cleanup policies that are due and runs them. Repsy Cloud triggers the same work
 * from an admin route; OS has no operator caller, so it polls in process. The poll is one indexed
 * query and does nothing unless a policy is enabled: a policy is created disabled and only a
 * manager of the repo enables it.
 *
 * <p>Set {@code repsy.docker.cleanup-policy.enabled} to {@code false} to switch the scheduler off
 * altogether; policies then never run, whatever their state. {@code interval} is the delay between
 * two passes, which is also how late an "actions/run" request can start.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@NullMarked
@ConditionalOnProperty(
    name = "repsy.docker.cleanup-policy.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class CleanupPolicyTask {

  private final CleanupPolicyFacade cleanupPolicyFacade;

  @Async(MaintenanceTaskExecutorConfig.BEAN_NAME)
  @Scheduled(
      initialDelayString = "${repsy.docker.cleanup-policy.initial-delay:PT2M}",
      fixedDelayString = "${repsy.docker.cleanup-policy.interval:PT1M}")
  public void run() {

    try {
      this.cleanupPolicyFacade.executeCleanup();
    } catch (final RuntimeException e) {
      log.error("Error during cleanup policy processing", e);
    }
  }
}
