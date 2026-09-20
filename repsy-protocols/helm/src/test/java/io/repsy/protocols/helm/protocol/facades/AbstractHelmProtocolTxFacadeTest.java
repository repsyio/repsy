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
package io.repsy.protocols.helm.protocol.facades;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartInfo;
import io.repsy.protocols.helm.shared.chart.services.AbstractHelmChartFilesService;
import io.repsy.protocols.helm.shared.chart.services.AbstractHelmChartFilesService.DeletedChart;
import io.repsy.protocols.helm.shared.chart.services.ChartService;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciBlobInfo;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestInfo;
import io.repsy.protocols.helm.shared.oci.services.OciBlobService;
import io.repsy.protocols.helm.shared.oci.services.OciManifestService;
import io.repsy.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractHelmProtocolTxFacade OCI blob uploads")
class AbstractHelmProtocolTxFacadeTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID UPLOAD_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final String REPO_NAME = "charts";
  private static final int BLOB_SIZE = 300;
  private static final String DIGEST = sha256Of(new byte[BLOB_SIZE]);

  @Mock private HelmStorageService<UUID> helmStorageService;
  @Mock private ChartService<UUID> chartService;
  @Mock private OciBlobService<UUID> ociBlobService;
  @Mock private OciManifestService<UUID> ociManifestService;
  @Mock private AbstractHelmChartFilesService<UUID> chartFilesService;
  @Mock private HelmOciBlobInfo blobInfo;
  @Mock private HelmChartInfo chartInfo;

  private TestFacade facade;
  private ProtocolContext context;

  private static class TestFacade extends AbstractHelmProtocolTxFacade<UUID> {

    TestFacade(
        final HelmStorageService<UUID> helmStorageService,
        final ChartService<UUID> chartService,
        final OciBlobService<UUID> ociBlobService,
        final OciManifestService<UUID> ociManifestService,
        final AbstractHelmChartFilesService<UUID> chartFilesService) {
      super(
          helmStorageService, chartService, ociBlobService, ociManifestService, chartFilesService);
    }
  }

  @BeforeEach
  void setUp() {
    this.facade =
        new TestFacade(
            this.helmStorageService,
            this.chartService,
            this.ociBlobService,
            this.ociManifestService,
            this.chartFilesService);

    final var repoInfo = new BaseRepoInfo<UUID>();
    repoInfo.setId(REPO_ID);
    repoInfo.setStorageKey(REPO_ID);
    repoInfo.setName(REPO_NAME);

    this.context = new ProtocolContext();
    this.context.addProperty(
        "urlProperties",
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName(REPO_NAME)
            .relativePath(new RelativePath("/"))
            .repoInfo(repoInfo)
            .build());
  }

  private long reportedUsage() {
    return this.context.<BaseUsages>getProperty("usages").getDiskUsage();
  }

  private static String sha256Of(final byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private void uploadHolds(final byte[] bytes) {
    when(this.helmStorageService.getBlob(REPO_ID, UPLOAD_ID.toString(), REPO_NAME))
        .thenReturn(Optional.of(new ByteArrayResource(bytes)));
  }

  private void blobIsStoredUnderDigest() {
    this.uploadHolds(new byte[BLOB_SIZE]);
    when(this.helmStorageService.getBlob(REPO_ID, DIGEST, REPO_NAME))
        .thenReturn(Optional.of(new ByteArrayResource(new byte[BLOB_SIZE])));
    when(this.ociBlobService.findOrCreate(any(), eq(REPO_ID))).thenReturn(this.blobInfo);
  }

  private static InputStream body() {
    return new ByteArrayInputStream(new byte[BLOB_SIZE]);
  }

  @Nested
  @DisplayName("uploadBlobChunk()")
  class UploadBlobChunk {

    @Test
    @DisplayName("reports the bytes the chunk added to the upload")
    void reportsWrittenBytes() throws Exception {
      when(AbstractHelmProtocolTxFacadeTest.this.helmStorageService.saveBlobChunk(
              eq(REPO_ID), eq(UPLOAD_ID), any(), eq(REPO_NAME)))
          .thenReturn(BaseUsages.ofDisk(BLOB_SIZE));
      when(AbstractHelmProtocolTxFacadeTest.this.helmStorageService.getBlobSize(
              REPO_ID, UPLOAD_ID, REPO_NAME))
          .thenReturn((long) BLOB_SIZE);

      final var size =
          AbstractHelmProtocolTxFacadeTest.this.facade.uploadBlobChunk(
              AbstractHelmProtocolTxFacadeTest.this.context, UPLOAD_ID, body(), BLOB_SIZE);

      assertThat(size).isEqualTo(BLOB_SIZE);
      assertThat(AbstractHelmProtocolTxFacadeTest.this.reportedUsage()).isEqualTo(BLOB_SIZE);
    }
  }

  @Nested
  @DisplayName("finalizeBlob()")
  class FinalizeBlob {

    @Test
    @DisplayName("nets the refund of a duplicate against the chunk sent with the finalize")
    void netsDuplicateAgainstItsOwnChunk() throws Exception {
      AbstractHelmProtocolTxFacadeTest.this.blobIsStoredUnderDigest();
      when(AbstractHelmProtocolTxFacadeTest.this.helmStorageService.saveBlobChunk(
              eq(REPO_ID), eq(UPLOAD_ID), any(), eq(REPO_NAME)))
          .thenReturn(BaseUsages.ofDisk(BLOB_SIZE));
      when(AbstractHelmProtocolTxFacadeTest.this.helmStorageService.finalizeBlob(
              REPO_ID, UPLOAD_ID, DIGEST))
          .thenReturn(BaseUsages.ofDisk(-BLOB_SIZE));

      final var info =
          AbstractHelmProtocolTxFacadeTest.this.facade.finalizeBlob(
              AbstractHelmProtocolTxFacadeTest.this.context,
              UPLOAD_ID,
              DIGEST,
              "application/octet-stream",
              body(),
              BLOB_SIZE);

      assertThat(info).isSameAs(AbstractHelmProtocolTxFacadeTest.this.blobInfo);
      assertThat(AbstractHelmProtocolTxFacadeTest.this.reportedUsage()).isZero();
    }

    @Test
    @DisplayName("reports the whole upload as a refund when a duplicate arrives after its chunks")
    void refundsDuplicateChargedByEarlierChunks() throws Exception {
      AbstractHelmProtocolTxFacadeTest.this.blobIsStoredUnderDigest();
      when(AbstractHelmProtocolTxFacadeTest.this.helmStorageService.finalizeBlob(
              REPO_ID, UPLOAD_ID, DIGEST))
          .thenReturn(BaseUsages.ofDisk(-BLOB_SIZE));

      AbstractHelmProtocolTxFacadeTest.this.facade.finalizeBlob(
          AbstractHelmProtocolTxFacadeTest.this.context,
          UPLOAD_ID,
          DIGEST,
          "application/octet-stream",
          body(),
          0);

      verify(AbstractHelmProtocolTxFacadeTest.this.helmStorageService, never())
          .saveBlobChunk(any(), any(), any(), any());
      assertThat(AbstractHelmProtocolTxFacadeTest.this.reportedUsage()).isEqualTo(-BLOB_SIZE);
    }

    @Test
    @DisplayName("adds the chunk sent with the finalize to the usage of a new blob")
    void chargesNewBlobSentWithTheFinalize() throws Exception {
      AbstractHelmProtocolTxFacadeTest.this.blobIsStoredUnderDigest();
      when(AbstractHelmProtocolTxFacadeTest.this.helmStorageService.saveBlobChunk(
              eq(REPO_ID), eq(UPLOAD_ID), any(), eq(REPO_NAME)))
          .thenReturn(BaseUsages.ofDisk(BLOB_SIZE));
      when(AbstractHelmProtocolTxFacadeTest.this.helmStorageService.finalizeBlob(
              REPO_ID, UPLOAD_ID, DIGEST))
          .thenReturn(BaseUsages.ofDisk(0));

      AbstractHelmProtocolTxFacadeTest.this.facade.finalizeBlob(
          AbstractHelmProtocolTxFacadeTest.this.context,
          UPLOAD_ID,
          DIGEST,
          "application/octet-stream",
          body(),
          BLOB_SIZE);

      assertThat(AbstractHelmProtocolTxFacadeTest.this.reportedUsage()).isEqualTo(BLOB_SIZE);
    }

    @Test
    @DisplayName("refuses an upload that does not hash to the claimed digest")
    void refusesDigestMismatch() {
      AbstractHelmProtocolTxFacadeTest.this.uploadHolds(new byte[BLOB_SIZE - 1]);
      when(AbstractHelmProtocolTxFacadeTest.this.helmStorageService.saveBlobChunk(
              eq(REPO_ID), eq(UPLOAD_ID), any(), eq(REPO_NAME)))
          .thenReturn(BaseUsages.ofDisk(BLOB_SIZE - 1));

      assertThatThrownBy(
              () ->
                  AbstractHelmProtocolTxFacadeTest.this.facade.finalizeBlob(
                      AbstractHelmProtocolTxFacadeTest.this.context,
                      UPLOAD_ID,
                      DIGEST,
                      "application/octet-stream",
                      new ByteArrayInputStream(new byte[BLOB_SIZE - 1]),
                      BLOB_SIZE - 1))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("digestMismatch");

      verify(AbstractHelmProtocolTxFacadeTest.this.helmStorageService, never())
          .finalizeBlob(any(), any(), any());
      verify(AbstractHelmProtocolTxFacadeTest.this.ociBlobService, never())
          .findOrCreate(any(), any());
    }

    @Test
    @DisplayName("refuses a digest that is not a well formed sha256 or sha512 digest")
    void refusesMalformedDigest() {
      AbstractHelmProtocolTxFacadeTest.this.uploadHolds(new byte[BLOB_SIZE]);

      assertThatThrownBy(
              () ->
                  AbstractHelmProtocolTxFacadeTest.this.facade.finalizeBlob(
                      AbstractHelmProtocolTxFacadeTest.this.context,
                      UPLOAD_ID,
                      "sha256:abc",
                      "application/octet-stream",
                      body(),
                      0))
          .isInstanceOf(BadRequestException.class);

      verify(AbstractHelmProtocolTxFacadeTest.this.helmStorageService, never())
          .finalizeBlob(any(), any(), any());
    }

    @Test
    @DisplayName("answers not found for an upload that holds no data")
    void refusesUnknownUpload() {
      when(AbstractHelmProtocolTxFacadeTest.this.helmStorageService.getBlob(
              REPO_ID, UPLOAD_ID.toString(), REPO_NAME))
          .thenReturn(Optional.empty());

      assertThatThrownBy(
              () ->
                  AbstractHelmProtocolTxFacadeTest.this.facade.finalizeBlob(
                      AbstractHelmProtocolTxFacadeTest.this.context,
                      UPLOAD_ID,
                      DIGEST,
                      "application/octet-stream",
                      body(),
                      0))
          .isInstanceOf(ItemNotFoundException.class);
    }
  }

  @Nested
  @DisplayName("pushChart()")
  class PushChart {

    @Test
    @DisplayName("rejects an existing version with a fixed msgId that carries no name or version")
    void rejectsExistingVersionWithFixedMsgId() {
      when(AbstractHelmProtocolTxFacadeTest.this.helmStorageService.getChartRelativePath(
              "payments", "1.0.0"))
          .thenReturn("charts/payments-1.0.0.tgz");
      when(AbstractHelmProtocolTxFacadeTest.this.chartService.findOptionalByNameAndVersion(
              REPO_ID, "payments", "1.0.0"))
          .thenReturn(Optional.of(AbstractHelmProtocolTxFacadeTest.this.chartInfo));

      assertThatThrownBy(
              () ->
                  AbstractHelmProtocolTxFacadeTest.this.facade.pushChart(
                      AbstractHelmProtocolTxFacadeTest.this.context,
                      "payments",
                      "1.0.0",
                      "",
                      "",
                      null,
                      DIGEST,
                      body(),
                      BLOB_SIZE))
          .isInstanceOf(ItemAlreadyExistException.class)
          .hasMessage("chartAlreadyExists");

      verify(AbstractHelmProtocolTxFacadeTest.this.helmStorageService, never())
          .saveChart(any(), any(), any());
    }
  }

  @Nested
  @DisplayName("pushManifest()")
  class PushManifest {

    @Test
    @DisplayName("reports the bytes the manifest file added, so the manifest is charged")
    void chargesTheManifestFile() throws Exception {
      final var content = "{}".getBytes(StandardCharsets.UTF_8);
      when(AbstractHelmProtocolTxFacadeTest.this.helmStorageService.saveManifest(
              REPO_ID, "payments", "1.0.0", content, REPO_NAME))
          .thenReturn(BaseUsages.ofDisk(content.length));

      AbstractHelmProtocolTxFacadeTest.this.facade.pushManifest(
          AbstractHelmProtocolTxFacadeTest.this.context, "payments", "1.0.0", content);

      assertThat(AbstractHelmProtocolTxFacadeTest.this.reportedUsage()).isEqualTo(content.length);
    }

    @Test
    @DisplayName("reports only the difference when a manifest of the same reference is replaced")
    void chargesOnlyTheDifferenceOnOverwrite() throws Exception {
      final var content = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
      when(AbstractHelmProtocolTxFacadeTest.this.helmStorageService.saveManifest(
              REPO_ID, "payments", "latest", content, REPO_NAME))
          .thenReturn(BaseUsages.ofDisk(3));

      AbstractHelmProtocolTxFacadeTest.this.facade.pushManifest(
          AbstractHelmProtocolTxFacadeTest.this.context, "payments", "latest", content);

      assertThat(AbstractHelmProtocolTxFacadeTest.this.reportedUsage()).isEqualTo(3);
    }
  }

  @Nested
  @DisplayName("deleteChart()")
  class DeleteChart {

    @Test
    @DisplayName("deletes the rows first, then the files, and releases everything they held")
    void releasesTheBytesOfEveryDeletedFile() throws Exception {
      final var manifest = mock(HelmOciManifestInfo.class);
      final var chartId = UUID.fromString("00000000-0000-0000-0000-000000000003");
      when(AbstractHelmProtocolTxFacadeTest.this.chartInfo.id()).thenReturn(chartId);
      when(AbstractHelmProtocolTxFacadeTest.this.chartInfo.digest()).thenReturn(DIGEST);
      when(AbstractHelmProtocolTxFacadeTest.this.chartService.findByRepoIdAndNameAndVersion(
              REPO_ID, "payments", "1.0.0"))
          .thenReturn(AbstractHelmProtocolTxFacadeTest.this.chartInfo);
      when(AbstractHelmProtocolTxFacadeTest.this.ociManifestService.findAllByChartId(chartId))
          .thenReturn(List.of(manifest));
      when(AbstractHelmProtocolTxFacadeTest.this.chartFilesService.deleteFiles(
              eq(REPO_ID),
              eq(REPO_ID),
              eq(REPO_NAME),
              eq(List.of(new DeletedChart("payments", "1.0.0", DIGEST, List.of(manifest))))))
          .thenReturn(1234L);

      AbstractHelmProtocolTxFacadeTest.this.facade.deleteChart(
          AbstractHelmProtocolTxFacadeTest.this.context, "payments", "1.0.0");

      final var order =
          inOrder(
              AbstractHelmProtocolTxFacadeTest.this.ociManifestService,
              AbstractHelmProtocolTxFacadeTest.this.chartService,
              AbstractHelmProtocolTxFacadeTest.this.chartFilesService);
      order
          .verify(AbstractHelmProtocolTxFacadeTest.this.ociManifestService)
          .deleteAllByChartId(chartId);
      order
          .verify(AbstractHelmProtocolTxFacadeTest.this.chartService)
          .delete(REPO_ID, "payments", "1.0.0");
      order
          .verify(AbstractHelmProtocolTxFacadeTest.this.chartFilesService)
          .deleteFiles(eq(REPO_ID), eq(REPO_ID), eq(REPO_NAME), any());
      assertThat(AbstractHelmProtocolTxFacadeTest.this.reportedUsage()).isEqualTo(-1234L);
    }
  }
}
