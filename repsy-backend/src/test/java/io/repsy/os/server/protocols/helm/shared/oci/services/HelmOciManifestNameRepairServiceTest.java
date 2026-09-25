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
package io.repsy.os.server.protocols.helm.shared.oci.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.os.server.protocols.helm.shared.chart.services.HelmChartService;
import io.repsy.os.server.protocols.helm.shared.oci.entities.HelmOciManifest;
import io.repsy.os.server.protocols.helm.shared.oci.repositories.HelmOciManifestMismatch;
import io.repsy.os.server.protocols.helm.shared.oci.repositories.HelmOciManifestRepository;
import io.repsy.os.server.protocols.helm.shared.storage.services.HelmStorageService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RPS-1392: the repair runs after the web server has started, so it takes the chart lock that
 * pushes and deletes take first (RPS-1365). The lock against a running push is a database
 * behaviour; what is pinned here is that the repair asks for it before it reads or changes a
 * manifest of the chart.
 */
@DisplayName("HelmOciManifestNameRepairService chart lock (RPS-1392)")
class HelmOciManifestNameRepairServiceTest {

  private static final UUID REPO_ID = UUID.randomUUID();
  private static final UUID MANIFEST_ID = UUID.randomUUID();

  private final HelmChartService chartService = mock(HelmChartService.class);
  private final HelmOciManifestRepository manifestRepository =
      mock(HelmOciManifestRepository.class);
  private final HelmStorageService storageService = mock(HelmStorageService.class);
  private final HelmOciManifestNameRepairService service =
      new HelmOciManifestNameRepairService(
          this.chartService, this.manifestRepository, this.storageService);

  private final HelmOciManifestMismatch mismatch =
      new HelmOciManifestMismatch(
          MANIFEST_ID, REPO_ID, "repo", "alias", "1.0.0", "sha256:a", "real-name");

  private HelmOciManifest stray() {
    final var manifest = new HelmOciManifest();
    manifest.setName("alias");
    manifest.setReference("1.0.0");
    manifest.setDigest("sha256:a");
    manifest.setContent("{}");
    return manifest;
  }

  @Test
  @DisplayName("locks the chart of a manifest before it reads or re-keys it")
  void locksChartBeforeTouchingTheManifest() {
    when(this.manifestRepository.findAllNamedDifferentlyFromChart())
        .thenReturn(List.of(this.mismatch));
    when(this.chartService.lockChartIfPresent(REPO_ID, "real-name")).thenReturn(true);
    when(this.manifestRepository.findById(MANIFEST_ID)).thenReturn(Optional.of(this.stray()));
    when(this.manifestRepository.findByRepoIdAndNameAndReference(REPO_ID, "real-name", "1.0.0"))
        .thenReturn(Optional.empty());

    final var report = this.service.repair();

    assertThat(report.rekeyed()).isEqualTo(1);
    final var order = inOrder(this.chartService, this.manifestRepository);
    order.verify(this.chartService).lockChartIfPresent(REPO_ID, "real-name");
    order.verify(this.manifestRepository).findById(MANIFEST_ID);
    order
        .verify(this.manifestRepository)
        .findByRepoIdAndNameAndReference(REPO_ID, "real-name", "1.0.0");
    order.verify(this.manifestRepository).saveAndFlush(any(HelmOciManifest.class));
  }

  @Test
  @DisplayName("skips a manifest whose chart was deleted since the list was read")
  void skipsChartThatIsGone() {
    when(this.manifestRepository.findAllNamedDifferentlyFromChart())
        .thenReturn(List.of(this.mismatch));
    when(this.chartService.lockChartIfPresent(REPO_ID, "real-name")).thenReturn(false);

    final var report = this.service.repair();

    assertThat(report.isEmpty()).isTrue();
    verify(this.manifestRepository, never()).findById(any());
    verify(this.manifestRepository, never()).saveAndFlush(any());
    verify(this.manifestRepository, never()).delete(any());
  }
}
