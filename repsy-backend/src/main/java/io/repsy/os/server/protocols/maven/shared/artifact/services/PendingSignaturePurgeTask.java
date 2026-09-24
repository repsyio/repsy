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
package io.repsy.os.server.protocols.maven.shared.artifact.services;

import io.repsy.os.config.async.MaintenanceTaskExecutorConfig;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes the Maven signatures that were parked for a file that never came (RPS-1188). A deploy
 * takes minutes, so a parked signature that is older than {@code repsy.maven.pending-signature.ttl}
 * (24 hours by default) will not find its file any more; a hand-made upload of the file may follow
 * hours later, which is why it is not shorter. It runs every {@code
 * repsy.maven.pending-signature.purge-interval} (15 minutes) and logs what it deleted. Set {@code
 * repsy.maven.pending-signature.enabled} to {@code false} to switch it off.
 */
@Component
@ConditionalOnProperty(
    name = "repsy.maven.pending-signature.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PendingSignaturePurgeTask {

  private final PendingSignatureService pendingSignatureService;
  private final Duration ttl;

  public PendingSignaturePurgeTask(
      final PendingSignatureService pendingSignatureService,
      @Value("${repsy.maven.pending-signature.ttl:PT24H}") final Duration ttl) {

    this.pendingSignatureService = pendingSignatureService;
    this.ttl = ttl;
  }

  @Async(MaintenanceTaskExecutorConfig.BEAN_NAME)
  @Scheduled(
      initialDelayString = "${repsy.maven.pending-signature.purge-initial-delay:PT1M}",
      fixedDelayString = "${repsy.maven.pending-signature.purge-interval:PT15M}")
  public void purge() {

    this.pendingSignatureService.purgeOlderThan(this.ttl);
  }
}
