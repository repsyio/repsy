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
package io.repsy.os.server.security.shared.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.repsy.os.server.protocols.helm.shared.chart.services.HelmChartService;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartInfo;
import io.repsy.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.protocols.shared.storage.RepoRef;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;

@ExtendWith(MockitoExtension.class)
@DisplayName("HelmArtifactStorageResolver classic-then-OCI order (RPS-1736)")
class HelmArtifactStorageResolverTest {

  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final String CLASSIC = "charts/payments-1.0.0.tgz";

  private final UUID repoId = UUID.randomUUID();

  @Mock HelmStorageService<UUID> storage;
  @Mock HelmChartService chartService;

  private HelmArtifactStorageResolver resolver() {
    return new HelmArtifactStorageResolver(this.storage, this.chartService);
  }

  private void givenChartRow() {
    final var info = mock(HelmChartInfo.class);
    when(info.digest()).thenReturn(DIGEST);
    when(this.chartService.findOptionalByNameAndVersion(this.repoId, "payments", "1.0.0"))
        .thenReturn(Optional.of(info));
  }

  @Test
  @DisplayName("a classic chart resolves to its charts/ path")
  void classicOnly() throws IOException {
    when(this.storage.getChartRelativePath("payments", "1.0.0")).thenReturn(CLASSIC);
    when(this.storage.findResource(any(), any()))
        .thenReturn(Optional.of(new ByteArrayResource(new byte[] {1})));

    assertThat(this.resolver().resolve(this.repoId, "repo", "payments", "1.0.0")).contains(CLASSIC);
  }

  @Test
  @DisplayName("an OCI-only chart resolves to its chart layer blob")
  void ociOnly() throws IOException {
    when(this.storage.getChartRelativePath("payments", "1.0.0")).thenReturn(CLASSIC);
    when(this.storage.findResource(any(), any())).thenReturn(Optional.empty());
    this.givenChartRow();
    when(this.storage.blobExists(new RepoRef(this.repoId, "repo"), DIGEST)).thenReturn(true);

    assertThat(this.resolver().resolve(this.repoId, "repo", "payments", "1.0.0"))
        .contains("oci/blobs/" + DIGEST);
  }

  @Test
  @DisplayName("a chart row whose blob is gone resolves to nothing")
  void rowWithoutBlob() throws IOException {
    when(this.storage.getChartRelativePath("payments", "1.0.0")).thenReturn(CLASSIC);
    when(this.storage.findResource(any(), any())).thenReturn(Optional.empty());
    this.givenChartRow();
    when(this.storage.blobExists(new RepoRef(this.repoId, "repo"), DIGEST)).thenReturn(false);

    assertThat(this.resolver().resolve(this.repoId, "repo", "payments", "1.0.0")).isEmpty();
  }

  @Test
  @DisplayName("a chart with neither a file nor a row resolves to nothing")
  void missing() throws IOException {
    when(this.storage.getChartRelativePath("payments", "1.0.0")).thenReturn(CLASSIC);
    when(this.storage.findResource(any(), any())).thenReturn(Optional.empty());
    when(this.chartService.findOptionalByNameAndVersion(this.repoId, "payments", "1.0.0"))
        .thenReturn(Optional.empty());

    assertThat(this.resolver().resolve(this.repoId, "repo", "payments", "1.0.0")).isEmpty();
  }
}
