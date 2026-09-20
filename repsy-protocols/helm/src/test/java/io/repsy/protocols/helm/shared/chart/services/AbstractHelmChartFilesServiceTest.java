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
package io.repsy.protocols.helm.shared.chart.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.protocols.helm.shared.chart.services.AbstractHelmChartFilesService.DeletedChart;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestInfo;
import io.repsy.protocols.helm.shared.oci.services.OciBlobService;
import io.repsy.protocols.helm.shared.oci.services.OciManifestService;
import io.repsy.protocols.helm.shared.storage.services.HelmStorageService;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractHelmChartFilesService")
class AbstractHelmChartFilesServiceTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String REPO_NAME = "charts";
  private static final String ARCHIVE = digest('a');
  private static final String CONFIG = digest('b');
  private static final String PROVENANCE = digest('c');

  @Mock private HelmStorageService<UUID> helmStorageService;
  @Mock private ChartService<UUID> chartService;
  @Mock private OciBlobService<UUID> ociBlobService;
  @Mock private OciManifestService<UUID> ociManifestService;

  private TestService service;

  private static class TestService extends AbstractHelmChartFilesService<UUID> {

    TestService(
        final HelmStorageService<UUID> helmStorageService,
        final ChartService<UUID> chartService,
        final OciBlobService<UUID> ociBlobService,
        final OciManifestService<UUID> ociManifestService) {
      super(helmStorageService, chartService, ociBlobService, ociManifestService);
    }
  }

  @BeforeEach
  void setUp() {
    this.service =
        new TestService(
            this.helmStorageService,
            this.chartService,
            this.ociBlobService,
            this.ociManifestService);
  }

  private static String digest(final char hex) {
    return "sha256:" + String.valueOf(hex).repeat(64);
  }

  private static String manifestJson(final String config, final String... layers) {
    final var layerJson = new StringBuilder();
    for (final var layer : layers) {
      layerJson.append(layerJson.isEmpty() ? "" : ",");
      layerJson.append("{\"digest\":\"").append(layer).append("\",\"size\":10}");
    }
    return "{\"schemaVersion\":2,\"config\":{\"digest\":\""
        + config
        + "\",\"size\":2},\"layers\":["
        + layerJson
        + "]}";
  }

  private static HelmOciManifestInfo manifest(final String reference, final String content) {
    final var manifest = mock(HelmOciManifestInfo.class);
    when(manifest.name()).thenReturn("payments");
    when(manifest.reference()).thenReturn(reference);
    when(manifest.content()).thenReturn(content);
    return manifest;
  }

  /** What the repo still holds once the charts are deleted: the JSON of each remaining manifest. */
  private void remainingManifests(final String... contents) {
    when(ociManifestService.streamContentByRepoId(REPO_ID))
        .thenAnswer(invocation -> Stream.of(contents));
  }

  @Nested
  @DisplayName("deleteFiles()")
  class DeleteFiles {

    @Test
    @DisplayName("deletes the archive, every manifest file and the config blob nothing references")
    void deletesTheChartsOwnFilesAndItsUnsharedBlobs() throws Exception {
      final var content = manifestJson(CONFIG, ARCHIVE);
      final var tags = List.of(manifest("1.0.0", content), manifest("stable", content));
      when(helmStorageService.deleteChartFile(REPO_ID, "payments-1.0.0.tgz", ARCHIVE, REPO_NAME))
          .thenReturn(1000L);
      when(helmStorageService.deleteManifestFile(REPO_ID, "payments", "1.0.0", REPO_NAME))
          .thenReturn(300L);
      when(helmStorageService.deleteManifestFile(REPO_ID, "payments", "stable", REPO_NAME))
          .thenReturn(300L);
      when(chartService.existsByRepoIdAndDigest(REPO_ID, ARCHIVE)).thenReturn(false);
      remainingManifests();
      when(helmStorageService.deleteBlob(REPO_ID, CONFIG, REPO_NAME)).thenReturn(2L);

      final var freed =
          service.deleteFiles(
              REPO_ID,
              REPO_ID,
              REPO_NAME,
              List.of(new DeletedChart("payments", "1.0.0", ARCHIVE, tags)));

      assertThat(freed).isEqualTo(1000L + 300L + 300L + 2L);
      verify(ociBlobService).deleteByRepoIdAndDigest(REPO_ID, ARCHIVE);
      verify(ociBlobService).deleteByRepoIdAndDigest(REPO_ID, CONFIG);
      // The archive is deleted with the chart, never as a shared blob.
      verify(helmStorageService, never()).deleteBlob(REPO_ID, ARCHIVE, REPO_NAME);
    }

    @Test
    @DisplayName("keeps a blob another manifest still references")
    void keepsABlobAnotherManifestReferences() throws Exception {
      final var tags = List.of(manifest("1.0.0", manifestJson(CONFIG, ARCHIVE, PROVENANCE)));
      remainingManifests("{\"config\":{\"digest\":\"" + CONFIG + "\"}}");
      when(helmStorageService.deleteBlob(REPO_ID, PROVENANCE, REPO_NAME)).thenReturn(40L);

      final var freed =
          service.deleteFiles(
              REPO_ID,
              REPO_ID,
              REPO_NAME,
              List.of(new DeletedChart("payments", "1.0.0", ARCHIVE, tags)));

      assertThat(freed).isEqualTo(40L);
      verify(helmStorageService, never()).deleteBlob(REPO_ID, CONFIG, REPO_NAME);
      verify(ociBlobService, never()).deleteByRepoIdAndDigest(REPO_ID, CONFIG);
      verify(ociBlobService).deleteByRepoIdAndDigest(REPO_ID, PROVENANCE);
    }

    @Test
    @DisplayName("keeps the archive of a chart that stays, even when a deleted manifest named it")
    void keepsTheArchiveOfAChartThatStays() throws Exception {
      final var retagged = digest('e');
      // The deleted chart's "latest" tag was pushed again for another chart, whose archive it
      // names.
      final var tags = List.of(manifest("latest", manifestJson(CONFIG, retagged)));
      when(chartService.existsByRepoIdAndDigest(REPO_ID, retagged)).thenReturn(true);
      remainingManifests();
      when(helmStorageService.deleteBlob(REPO_ID, CONFIG, REPO_NAME)).thenReturn(2L);

      final var freed =
          service.deleteFiles(
              REPO_ID,
              REPO_ID,
              REPO_NAME,
              List.of(new DeletedChart("payments", "1.0.0", ARCHIVE, tags)));

      assertThat(freed).isEqualTo(2L);
      verify(helmStorageService, never()).deleteBlob(REPO_ID, retagged, REPO_NAME);
      verify(ociBlobService, never()).deleteByRepoIdAndDigest(REPO_ID, retagged);
    }

    @Test
    @DisplayName("checks a blob shared by several deleted versions once")
    void checksASharedBlobOnce() throws Exception {
      final var other = digest('d');
      final var first = List.of(manifest("1.0.0", manifestJson(CONFIG, ARCHIVE)));
      final var second = List.of(manifest("1.1.0", manifestJson(CONFIG, other)));
      remainingManifests();
      when(helmStorageService.deleteBlob(REPO_ID, CONFIG, REPO_NAME)).thenReturn(2L);

      final var freed =
          service.deleteFiles(
              REPO_ID,
              REPO_ID,
              REPO_NAME,
              List.of(
                  new DeletedChart("payments", "1.0.0", ARCHIVE, first),
                  new DeletedChart("payments", "1.1.0", other, second)));

      assertThat(freed).isEqualTo(2L);
      verify(helmStorageService).deleteBlob(REPO_ID, CONFIG, REPO_NAME);
    }

    @Test
    @DisplayName("leaves the archive blob row of a chart that another chart row still uses")
    void keepsTheArchiveRowOfASharedArchive() throws Exception {
      final var tags = List.of(manifest("1.0.0", manifestJson(CONFIG, ARCHIVE)));
      when(chartService.existsByRepoIdAndDigest(REPO_ID, ARCHIVE)).thenReturn(true);
      remainingManifests();

      service.deleteFiles(
          REPO_ID,
          REPO_ID,
          REPO_NAME,
          List.of(new DeletedChart("payments", "1.0.0", ARCHIVE, tags)));

      verify(ociBlobService, never()).deleteByRepoIdAndDigest(REPO_ID, ARCHIVE);
    }

    @Test
    @DisplayName("a chart uploaded the classic way has no manifests, so only its archive goes")
    void classicChartHasNoBlobsToRelease() throws Exception {
      when(helmStorageService.deleteChartFile(REPO_ID, "payments-1.0.0.tgz", ARCHIVE, REPO_NAME))
          .thenReturn(900L);

      final var freed =
          service.deleteFiles(
              REPO_ID,
              REPO_ID,
              REPO_NAME,
              List.of(new DeletedChart("payments", "1.0.0", ARCHIVE, List.of())));

      assertThat(freed).isEqualTo(900L);
      verifyNoInteractions(ociBlobService, ociManifestService);
    }
  }

  @Nested
  @DisplayName("referencedDigests()")
  class ReferencedDigests {

    @Test
    @DisplayName("names the config and every layer")
    void namesConfigAndLayers() {
      assertThat(
              AbstractHelmChartFilesService.referencedDigests(
                  manifestJson(CONFIG, ARCHIVE, PROVENANCE)))
          .containsExactly(CONFIG, ARCHIVE, PROVENANCE);
    }

    @Test
    @DisplayName("skips entries without a well-formed sha256 digest")
    void skipsMalformedDigests() {
      final var content =
          "{\"config\":{\"digest\":\"sha256:short\"},"
              + "\"layers\":[{\"size\":1},{\"digest\":7},{\"digest\":\""
              + ARCHIVE
              + "\"}]}";

      assertThat(AbstractHelmChartFilesService.referencedDigests(content)).containsExactly(ARCHIVE);
    }

    @Test
    @DisplayName("a manifest without config or layers names nothing")
    void namesNothingWithoutConfigOrLayers() {
      assertThat(AbstractHelmChartFilesService.referencedDigests("{}")).isEmpty();
      assertThat(AbstractHelmChartFilesService.referencedDigests("{\"layers\":\"nope\"}"))
          .isEmpty();
    }

    @Test
    @DisplayName("a manifest that is not JSON names nothing")
    void namesNothingWhenNotJson() {
      assertThat(AbstractHelmChartFilesService.referencedDigests("not json")).isEmpty();
    }
  }
}
