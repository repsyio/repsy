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
package io.repsy.os.server.security.shared.listeners;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.RetryableException;
import io.repsy.core.events.ArtifactPushedEvent;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.server.security.scan.services.VulnerabilityScanTxService;
import io.repsy.os.server.security.scanner.VulnerabilityScannerRegistry;
import io.repsy.os.shared.repo.services.RepoTxService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("ArtifactScanListener")
class ArtifactScanListenerTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID SCAN_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final String SATURATED_MESSAGE = "Scan executor is saturated; retry later";

  @Mock private VulnerabilityScannerRegistry scannerRegistry;
  @Mock private VulnerabilityScanTxService scanTxService;
  @Mock private RepoTxService repoTxService;
  @Mock private DockerScanTokenIssuer dockerScanTokenIssuer;
  @Mock private Executor scanTaskExecutor;

  private ArtifactScanListener listener;

  @BeforeEach
  void setUp() {
    this.listener =
        new ArtifactScanListener(
            this.scannerRegistry,
            this.scanTxService,
            this.repoTxService,
            this.dockerScanTokenIssuer,
            this.scanTaskExecutor,
            Map.<String, StorageStrategy>of());
  }

  @Test
  @DisplayName("fails a pushed scan when the executor rejects it")
  void rejectedPushedScanIsFailed() {
    final var event = this.event();
    when(this.scannerRegistry.findScanner("MAVEN")).thenReturn(Optional.empty());
    when(this.scanTxService.createPendingScan(REPO_ID, "artifact", "1.0.0", "none"))
        .thenReturn(SCAN_ID);
    doThrow(new RejectedExecutionException())
        .when(this.scanTaskExecutor)
        .execute(org.mockito.ArgumentMatchers.any());

    this.listener.handleArtifactPushed(event);

    verify(this.scanTxService).recordScanFailure(SCAN_ID, SATURATED_MESSAGE);
  }

  @Test
  @DisplayName("returns a retryable failure when a manual scan is rejected")
  void rejectedManualScanIsRetryable() {
    doThrow(new RejectedExecutionException())
        .when(this.scanTaskExecutor)
        .execute(org.mockito.ArgumentMatchers.any());

    assertThatThrownBy(() -> this.listener.executeManualScan(SCAN_ID, this.event()))
        .isInstanceOf(RetryableException.class)
        .hasMessage("scanExecutorSaturated");

    verify(this.scanTxService).recordScanFailure(SCAN_ID, SATURATED_MESSAGE);
  }

  private ArtifactPushedEvent event() {
    return new ArtifactPushedEvent(
        REPO_ID, "MAVEN", "repo", "artifact/1.0.0/artifact.jar", "artifact", "1.0.0", false, false);
  }
}
