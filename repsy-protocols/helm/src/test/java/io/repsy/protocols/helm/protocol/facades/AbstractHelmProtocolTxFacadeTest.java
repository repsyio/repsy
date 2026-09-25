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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartForm;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartInfo;
import io.repsy.protocols.helm.shared.chart.services.AbstractHelmChartFilesService;
import io.repsy.protocols.helm.shared.chart.services.AbstractHelmChartFilesService.DeletedChart;
import io.repsy.protocols.helm.shared.chart.services.ChartService;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciBlobInfo;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestForm;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestInfo;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestPushForm;
import io.repsy.protocols.helm.shared.oci.services.OciBlobService;
import io.repsy.protocols.helm.shared.oci.services.OciManifestService;
import io.repsy.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
  @DisplayName("getUploadSize()")
  class GetUploadSize {

    @Test
    @DisplayName("reports the size of the bytes written so far")
    void reportsTheWrittenSize() throws Exception {
      AbstractHelmProtocolTxFacadeTest.this.uploadHolds(new byte[BLOB_SIZE]);

      final var size =
          AbstractHelmProtocolTxFacadeTest.this.facade.getUploadSize(
              AbstractHelmProtocolTxFacadeTest.this.context, UPLOAD_ID);

      assertThat(size).isEqualTo(BLOB_SIZE);
    }

    @Test
    @DisplayName("refuses an upload session that was never written")
    void refusesAMissingSession() {
      when(AbstractHelmProtocolTxFacadeTest.this.helmStorageService.getBlob(
              REPO_ID, UPLOAD_ID.toString(), REPO_NAME))
          .thenReturn(Optional.empty());

      assertThatThrownBy(
              () ->
                  AbstractHelmProtocolTxFacadeTest.this.facade.getUploadSize(
                      AbstractHelmProtocolTxFacadeTest.this.context, UPLOAD_ID))
          .isInstanceOf(ItemNotFoundException.class)
          .hasMessage("blobNotFound");
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

    private static final String CHART_PATH = "charts/payments-1.0.0.tgz";

    private BaseRepoInfo<UUID> repo() {
      return ProtocolContextUtils.<UUID>getRepoInfo(AbstractHelmProtocolTxFacadeTest.this.context);
    }

    /** Runs the file writer the facade hands to {@code publish}, as the service would. */
    private void publishRunsTheWriterWith(final HelmChartInfo replaced) throws Exception {
      when(AbstractHelmProtocolTxFacadeTest.this.helmStorageService.getChartRelativePath(
              "payments", "1.0.0"))
          .thenReturn(CHART_PATH);
      when(AbstractHelmProtocolTxFacadeTest.this.chartService.publish(
              eq(REPO_ID), any(HelmChartForm.class), anyBoolean(), any()))
          .thenAnswer(
              invocation -> {
                invocation.<ChartService.ChartFileWriter>getArgument(3).write(replaced);
                return AbstractHelmProtocolTxFacadeTest.this.chartInfo;
              });
    }

    private HelmChartInfo push() throws Exception {
      return AbstractHelmProtocolTxFacadeTest.this.facade.pushChart(
          AbstractHelmProtocolTxFacadeTest.this.context,
          "payments",
          "1.0.0",
          "",
          "",
          null,
          DIGEST,
          body(),
          BLOB_SIZE);
    }

    @Test
    @DisplayName("hands the row to the chart service before any file is written")
    void writesTheFileOnlyThroughThePublishCallback() throws Exception {
      when(AbstractHelmProtocolTxFacadeTest.this.helmStorageService.getChartRelativePath(
              "payments", "1.0.0"))
          .thenReturn(CHART_PATH);
      when(AbstractHelmProtocolTxFacadeTest.this.chartService.publish(
              eq(REPO_ID), any(HelmChartForm.class), eq(true), any()))
          .thenReturn(AbstractHelmProtocolTxFacadeTest.this.chartInfo);
      this.repo().setAllowOverride(true);

      final var info = this.push();

      assertThat(info).isSameAs(AbstractHelmProtocolTxFacadeTest.this.chartInfo);
      verify(AbstractHelmProtocolTxFacadeTest.this.helmStorageService, never())
          .saveChart(any(), any(), any());
    }

    @Test
    @DisplayName("propagates the conflict of the chart service and writes nothing")
    void propagatesTheConflict() throws Exception {
      when(AbstractHelmProtocolTxFacadeTest.this.helmStorageService.getChartRelativePath(
              "payments", "1.0.0"))
          .thenReturn(CHART_PATH);
      when(AbstractHelmProtocolTxFacadeTest.this.chartService.publish(
              eq(REPO_ID), any(HelmChartForm.class), eq(false), any()))
          .thenThrow(new ItemAlreadyExistException("chartAlreadyExists"));

      assertThatThrownBy(this::push)
          .isInstanceOf(ItemAlreadyExistException.class)
          .hasMessage("chartAlreadyExists");

      verify(AbstractHelmProtocolTxFacadeTest.this.helmStorageService, never())
          .saveChart(any(), any(), any());
    }

    @Test
    @DisplayName("stores a new version and charges its whole size")
    void chargesTheWholeSizeOfANewVersion() throws Exception {
      this.publishRunsTheWriterWith(null);

      this.push();

      verify(AbstractHelmProtocolTxFacadeTest.this.helmStorageService)
          .saveChart(eq(REPO_NAME), any(), any());
      assertThat(AbstractHelmProtocolTxFacadeTest.this.reportedUsage()).isEqualTo(BLOB_SIZE);
      assertThat(AbstractHelmProtocolTxFacadeTest.this.context.<String>getProperty("storagePath"))
          .isEqualTo(CHART_PATH);
      assertThat(AbstractHelmProtocolTxFacadeTest.this.context.<String>getProperty("artifactName"))
          .isEqualTo("payments");
    }

    @Test
    @DisplayName("charges only the difference when a version is replaced")
    void chargesTheDifferenceOfAReplacedVersion() throws Exception {
      when(AbstractHelmProtocolTxFacadeTest.this.chartInfo.size()).thenReturn(100L);
      this.publishRunsTheWriterWith(AbstractHelmProtocolTxFacadeTest.this.chartInfo);

      this.push();

      assertThat(AbstractHelmProtocolTxFacadeTest.this.reportedUsage()).isEqualTo(BLOB_SIZE - 100L);
    }

    @Test
    @DisplayName("removes the partly written file of a new version when the write fails")
    void removesThePartialFileOfANewVersion() throws Exception {
      this.publishRunsTheWriterWith(null);
      final var failure = new IllegalStateException("disk full");
      doThrow(failure)
          .when(AbstractHelmProtocolTxFacadeTest.this.helmStorageService)
          .saveChart(any(), any(), any());

      assertThatThrownBy(this::push).isSameAs(failure);

      verify(AbstractHelmProtocolTxFacadeTest.this.helmStorageService)
          .deleteChart(any(), eq(REPO_NAME));
      assertThat(AbstractHelmProtocolTxFacadeTest.this.context.<Object>getProperty("usages"))
          .isNull();
    }

    @Test
    @DisplayName("keeps the file of a replaced version when the write fails")
    void keepsTheFileOfAReplacedVersion() throws Exception {
      this.publishRunsTheWriterWith(AbstractHelmProtocolTxFacadeTest.this.chartInfo);
      doThrow(new IllegalStateException("disk full"))
          .when(AbstractHelmProtocolTxFacadeTest.this.helmStorageService)
          .saveChart(any(), any(), any());

      assertThatThrownBy(this::push).isInstanceOf(IllegalStateException.class);

      verify(AbstractHelmProtocolTxFacadeTest.this.helmStorageService, never())
          .deleteChart(any(), any());
    }

    @Test
    @DisplayName("still reports the write failure when the partial file cannot be removed")
    void reportsTheWriteFailureWhenCleanupFails() throws Exception {
      this.publishRunsTheWriterWith(null);
      final var failure = new IllegalStateException("disk full");
      doThrow(failure)
          .when(AbstractHelmProtocolTxFacadeTest.this.helmStorageService)
          .saveChart(any(), any(), any());
      when(AbstractHelmProtocolTxFacadeTest.this.helmStorageService.deleteChart(
              any(), eq(REPO_NAME)))
          .thenThrow(new IOException("no such file"));

      assertThatThrownBy(this::push)
          .isSameAs(failure)
          .satisfies(e -> assertThat(e.getSuppressed()).hasSize(1));
    }
  }

  @Nested
  @DisplayName("pushManifest() (RPS-1354)")
  class PushManifest {

    private final byte[] content = "{}".getBytes(StandardCharsets.UTF_8);
    private final UUID chartId = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private final HelmOciManifestInfo manifestInfo = mock(HelmOciManifestInfo.class);

    private HelmOciManifestPushForm form() {
      return HelmOciManifestPushForm.builder()
          .chart(HelmChartForm.builder().name("payments").version("1.0.0").digest(DIGEST).build())
          .name("payments")
          .reference("1.0.0")
          .digest("sha256:" + "b".repeat(64))
          .mediaType("application/vnd.oci.image.manifest.v1+json")
          .content("{}")
          .build();
    }

    private void rowsAreWritten(final boolean manifestExists) {
      final var it = AbstractHelmProtocolTxFacadeTest.this;
      when(it.chartInfo.id()).thenReturn(this.chartId);
      when(it.chartService.findOrCreate(any(HelmChartForm.class), eq(REPO_ID)))
          .thenReturn(it.chartInfo);
      when(it.ociManifestService.findByNameAndReference(REPO_ID, "payments", "1.0.0"))
          .thenReturn(manifestExists ? Optional.of(this.manifestInfo) : Optional.empty());
      when(it.ociManifestService.save(any(HelmOciManifestForm.class), eq(REPO_ID)))
          .thenReturn(this.manifestInfo);
    }

    private void fileWriteFails(final RuntimeException failure) {
      doThrow(failure)
          .when(AbstractHelmProtocolTxFacadeTest.this.helmStorageService)
          .saveManifest(REPO_ID, "payments", "1.0.0", this.content, REPO_NAME);
    }

    @Test
    @DisplayName("writes the chart version, then the manifest that points at it, then the file")
    void writesTheChartTheManifestAndTheFileInThatOrder() throws Exception {
      final var it = AbstractHelmProtocolTxFacadeTest.this;
      this.rowsAreWritten(false);
      when(it.helmStorageService.saveManifest(
              REPO_ID, "payments", "1.0.0", this.content, REPO_NAME))
          .thenReturn(BaseUsages.ofDisk(this.content.length));

      final var result = it.facade.pushManifest(it.context, this.form(), this.content);

      assertThat(result.manifest()).isSameAs(this.manifestInfo);
      final var order = inOrder(it.chartService, it.ociManifestService, it.helmStorageService);
      order.verify(it.chartService).findOrCreate(any(HelmChartForm.class), eq(REPO_ID));
      final var manifestForm = ArgumentCaptor.forClass(HelmOciManifestForm.class);
      order.verify(it.ociManifestService).save(manifestForm.capture(), eq(REPO_ID));
      assertThat(manifestForm.getValue().getChartId()).isEqualTo(this.chartId);
      order
          .verify(it.helmStorageService)
          .saveManifest(REPO_ID, "payments", "1.0.0", this.content, REPO_NAME);
    }

    @Test
    @DisplayName("hands the bytes the manifest file added back instead of charging them itself")
    void returnsTheUsagesWithoutChargingThem() throws Exception {
      final var it = AbstractHelmProtocolTxFacadeTest.this;
      this.rowsAreWritten(true);
      when(it.helmStorageService.saveManifest(
              REPO_ID, "payments", "1.0.0", this.content, REPO_NAME))
          .thenReturn(BaseUsages.ofDisk(3));

      final var result = it.facade.pushManifest(it.context, this.form(), this.content);

      assertThat(result.usages().getDiskUsage()).isEqualTo(3);
      assertThat((BaseUsages) it.context.getProperty("usages"))
          .as("the caller charges it once the transaction has committed")
          .isNull();
    }

    @Test
    @DisplayName("removes the partial file of a new manifest whose file cannot be written")
    void removesThePartialFileOfANewManifest() throws Exception {
      final var it = AbstractHelmProtocolTxFacadeTest.this;
      this.rowsAreWritten(false);
      final var failure = new IllegalStateException("disk full");
      this.fileWriteFails(failure);

      assertThatThrownBy(() -> it.facade.pushManifest(it.context, this.form(), this.content))
          .isSameAs(failure);

      verify(it.helmStorageService).deleteManifestFile(REPO_ID, "payments", "1.0.0", REPO_NAME);
    }

    @Test
    @DisplayName("leaves the file of a replaced manifest alone when the new one cannot be written")
    void leavesTheFileOfAReplacedManifest() throws Exception {
      final var it = AbstractHelmProtocolTxFacadeTest.this;
      this.rowsAreWritten(true);
      final var failure = new IllegalStateException("disk full");
      this.fileWriteFails(failure);

      assertThatThrownBy(() -> it.facade.pushManifest(it.context, this.form(), this.content))
          .isSameAs(failure);

      verify(it.helmStorageService, never()).deleteManifestFile(any(), any(), any(), any());
    }

    @Test
    @DisplayName("still reports the write failure when the partial file cannot be removed")
    void reportsTheWriteFailureWhenCleanupFails() throws Exception {
      final var it = AbstractHelmProtocolTxFacadeTest.this;
      this.rowsAreWritten(false);
      final var failure = new IllegalStateException("disk full");
      this.fileWriteFails(failure);
      when(it.helmStorageService.deleteManifestFile(REPO_ID, "payments", "1.0.0", REPO_NAME))
          .thenThrow(new IOException("no such file"));

      assertThatThrownBy(() -> it.facade.pushManifest(it.context, this.form(), this.content))
          .isSameAs(failure)
          .satisfies(e -> assertThat(e.getSuppressed()).hasSize(1));
    }

    /**
     * Runs the push with transaction synchronization active, as it is inside the facade's
     * transaction, and ends the transaction with {@code status}.
     */
    private void pushThenComplete(final int status) throws Exception {
      final var it = AbstractHelmProtocolTxFacadeTest.this;
      TransactionSynchronizationManager.initSynchronization();
      try {
        it.facade.pushManifest(it.context, this.form(), this.content);
        for (final var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
          synchronization.afterCompletion(status);
        }
      } finally {
        TransactionSynchronizationManager.clearSynchronization();
      }
    }

    private void fileHolds(final byte[]... contents) {
      final var it = AbstractHelmProtocolTxFacadeTest.this;
      final var reads = new ArrayList<Optional<Resource>>();
      for (final var bytes : contents) {
        reads.add(Optional.of(new ByteArrayResource(bytes)));
      }
      final var first = reads.remove(0);
      when(it.helmStorageService.getManifest(REPO_ID, "payments", "1.0.0", REPO_NAME))
          .thenReturn(first, reads.toArray(new Optional[0]));
    }

    @Test
    @DisplayName("removes the file of a new manifest when the transaction rolls back (RPS-1366)")
    void removesTheFileOfANewManifestWhenTheCommitFails() throws Exception {
      final var it = AbstractHelmProtocolTxFacadeTest.this;
      this.rowsAreWritten(false);
      this.fileHolds(this.content);

      this.pushThenComplete(TransactionSynchronization.STATUS_ROLLED_BACK);

      verify(it.helmStorageService).deleteManifestFile(REPO_ID, "payments", "1.0.0", REPO_NAME);
    }

    @Test
    @DisplayName("keeps the file of a manifest whose transaction committed (RPS-1366)")
    void keepsTheFileWhenTheTransactionCommits() throws Exception {
      final var it = AbstractHelmProtocolTxFacadeTest.this;
      this.rowsAreWritten(false);

      this.pushThenComplete(TransactionSynchronization.STATUS_COMMITTED);

      verify(it.helmStorageService, never()).deleteManifestFile(any(), any(), any(), any());
      verify(it.helmStorageService, never()).getManifest(any(), any(), any(), any());
    }

    @Test
    @DisplayName("keeps the file when the outcome of the commit is unknown (RPS-1366)")
    void keepsTheFileWhenTheOutcomeIsUnknown() throws Exception {
      final var it = AbstractHelmProtocolTxFacadeTest.this;
      this.rowsAreWritten(false);

      this.pushThenComplete(TransactionSynchronization.STATUS_UNKNOWN);

      verify(it.helmStorageService, never()).deleteManifestFile(any(), any(), any(), any());
    }

    @Test
    @DisplayName("puts back the bytes of a replaced manifest when the transaction rolls back")
    void restoresTheFileOfAReplacedManifest() throws Exception {
      final var it = AbstractHelmProtocolTxFacadeTest.this;
      final var previous = "{\"previous\":true}".getBytes(StandardCharsets.UTF_8);
      this.rowsAreWritten(true);
      // Read before the write to keep, and after the rollback to see the file is still ours.
      this.fileHolds(previous, this.content);

      this.pushThenComplete(TransactionSynchronization.STATUS_ROLLED_BACK);

      verify(it.helmStorageService).saveManifest(REPO_ID, "payments", "1.0.0", previous, REPO_NAME);
      verify(it.helmStorageService, never()).deleteManifestFile(any(), any(), any(), any());
    }

    @Test
    @DisplayName("leaves a file that a later push has already written again alone")
    void leavesAFileThatAnotherPushWrote() throws Exception {
      final var it = AbstractHelmProtocolTxFacadeTest.this;
      this.rowsAreWritten(false);
      this.fileHolds("{\"later\":true}".getBytes(StandardCharsets.UTF_8));

      this.pushThenComplete(TransactionSynchronization.STATUS_ROLLED_BACK);

      verify(it.helmStorageService, never()).deleteManifestFile(any(), any(), any(), any());
    }

    @Test
    @DisplayName("leaves a replaced manifest's file when the bytes it had could not be read")
    void leavesAReplacedFileWithNoPreviousBytes() throws Exception {
      final var it = AbstractHelmProtocolTxFacadeTest.this;
      this.rowsAreWritten(true);
      when(it.helmStorageService.getManifest(REPO_ID, "payments", "1.0.0", REPO_NAME))
          .thenReturn(Optional.empty(), Optional.of(new ByteArrayResource(this.content)));

      this.pushThenComplete(TransactionSynchronization.STATUS_ROLLED_BACK);

      verify(it.helmStorageService, never()).deleteManifestFile(any(), any(), any(), any());
      verify(it.helmStorageService, times(1)).saveManifest(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("does not fail the rolled back transaction when the file cannot be removed")
    void survivesAFailingCleanup() throws Exception {
      final var it = AbstractHelmProtocolTxFacadeTest.this;
      this.rowsAreWritten(false);
      this.fileHolds(this.content);
      when(it.helmStorageService.deleteManifestFile(REPO_ID, "payments", "1.0.0", REPO_NAME))
          .thenThrow(new IOException("no such file"));

      this.pushThenComplete(TransactionSynchronization.STATUS_ROLLED_BACK);

      verify(it.helmStorageService).deleteManifestFile(REPO_ID, "payments", "1.0.0", REPO_NAME);
    }

    @Test
    @DisplayName("never writes the file when the rows are refused")
    void neverWritesTheFileWhenTheManifestRowIsRefused() {
      final var it = AbstractHelmProtocolTxFacadeTest.this;
      final var lost = new OptimisticLockingFailureException("lost");
      when(it.chartInfo.id()).thenReturn(this.chartId);
      when(it.chartService.findOrCreate(any(HelmChartForm.class), eq(REPO_ID)))
          .thenReturn(it.chartInfo);
      when(it.ociManifestService.findByNameAndReference(REPO_ID, "payments", "1.0.0"))
          .thenReturn(Optional.empty());
      when(it.ociManifestService.save(any(HelmOciManifestForm.class), eq(REPO_ID))).thenThrow(lost);

      assertThatThrownBy(() -> it.facade.pushManifest(it.context, this.form(), this.content))
          .isSameAs(lost);

      verifyNoInteractions(it.helmStorageService);
    }

    @Test
    @DisplayName("writes neither the manifest nor the file when the chart version is refused")
    void writesNothingWhenTheChartRowIsRefused() {
      final var it = AbstractHelmProtocolTxFacadeTest.this;
      final var lost = new DataIntegrityViolationException("unique index");
      when(it.chartService.findOrCreate(any(HelmChartForm.class), eq(REPO_ID))).thenThrow(lost);

      assertThatThrownBy(() -> it.facade.pushManifest(it.context, this.form(), this.content))
          .isSameAs(lost);

      verifyNoInteractions(it.ociManifestService, it.helmStorageService);
    }
  }

  @Nested
  @DisplayName("getChart()")
  class GetChart {

    private static final String FILENAME = "payments-1.0.0.tgz";

    @Test
    @DisplayName("returns the classic resource directly and never scans the chart rows")
    void classicResourcePresentIsReturnedDirectly() throws Exception {
      final var it = AbstractHelmProtocolTxFacadeTest.this;
      final var classicResource = new ByteArrayResource(new byte[] {1, 2, 3});
      when(it.helmStorageService.getResource(any(), eq(REPO_NAME)))
          .thenReturn(Optional.of(classicResource));

      final var result = it.facade.getChart(it.context, FILENAME);

      assertThat(result).isSameAs(classicResource);
      verify(it.helmStorageService, never()).getBlob(any(), any(), any());
      verify(it.chartService, never()).findAllByRepoId(any());
    }

    @Test
    @DisplayName(
        "falls back to the OCI blob of the matching chart row when the classic file is absent")
    void classicAbsentFallsBackToOciBlob() throws Exception {
      final var it = AbstractHelmProtocolTxFacadeTest.this;
      final var blobResource = new ByteArrayResource(new byte[] {4, 5, 6});
      when(it.helmStorageService.getResource(any(), eq(REPO_NAME))).thenReturn(Optional.empty());
      when(it.chartInfo.name()).thenReturn("payments");
      when(it.chartInfo.version()).thenReturn("1.0.0");
      when(it.chartInfo.digest()).thenReturn(DIGEST);
      when(it.chartService.findAllByRepoId(REPO_ID)).thenReturn(List.of(it.chartInfo));
      when(it.helmStorageService.getBlob(REPO_ID, DIGEST, REPO_NAME))
          .thenReturn(Optional.of(blobResource));

      final var result = it.facade.getChart(it.context, FILENAME);

      assertThat(result).isSameAs(blobResource);
      verify(it.helmStorageService).getBlob(REPO_ID, DIGEST, REPO_NAME);
    }

    @Test
    @DisplayName("answers chartNotFound when no chart row matches the filename")
    void classicAbsentNoMatchingRow() throws Exception {
      final var it = AbstractHelmProtocolTxFacadeTest.this;
      when(it.helmStorageService.getResource(any(), eq(REPO_NAME))).thenReturn(Optional.empty());
      when(it.chartService.findAllByRepoId(REPO_ID)).thenReturn(List.of());

      assertThatThrownBy(() -> it.facade.getChart(it.context, FILENAME))
          .isInstanceOf(ItemNotFoundException.class)
          .hasMessage("chartNotFound");
    }

    @Test
    @DisplayName("answers chartNotFound when the matching row's blob is also absent")
    void classicAbsentRowMatchesButBlobAbsent() throws Exception {
      final var it = AbstractHelmProtocolTxFacadeTest.this;
      when(it.helmStorageService.getResource(any(), eq(REPO_NAME))).thenReturn(Optional.empty());
      when(it.chartInfo.name()).thenReturn("payments");
      when(it.chartInfo.version()).thenReturn("1.0.0");
      when(it.chartInfo.digest()).thenReturn(DIGEST);
      when(it.chartService.findAllByRepoId(REPO_ID)).thenReturn(List.of(it.chartInfo));
      when(it.helmStorageService.getBlob(REPO_ID, DIGEST, REPO_NAME)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> it.facade.getChart(it.context, FILENAME))
          .isInstanceOf(ItemNotFoundException.class)
          .hasMessage("chartNotFound");
    }

    @Test
    @DisplayName(
        "resolves a chart whose name itself contains hyphens, not a naive last-hyphen split")
    void resolvesNameWithHyphens() throws Exception {
      final var it = AbstractHelmProtocolTxFacadeTest.this;
      final var blobResource = new ByteArrayResource(new byte[] {7, 8, 9});
      final var hyphenatedChart = mock(HelmChartInfo.class);
      when(it.helmStorageService.getResource(any(), eq(REPO_NAME))).thenReturn(Optional.empty());
      when(hyphenatedChart.name()).thenReturn("my-chart-with-dashes");
      when(hyphenatedChart.version()).thenReturn("1.2.3");
      when(hyphenatedChart.digest()).thenReturn(DIGEST);
      when(it.chartService.findAllByRepoId(REPO_ID)).thenReturn(List.of(hyphenatedChart));
      when(it.helmStorageService.getBlob(REPO_ID, DIGEST, REPO_NAME))
          .thenReturn(Optional.of(blobResource));

      final var result = it.facade.getChart(it.context, "my-chart-with-dashes-1.2.3.tgz");

      assertThat(result).isSameAs(blobResource);
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
      // The chart is locked before anything else of it is read or deleted, the order a push takes
      // its locks in (RPS-1365).
      order
          .verify(AbstractHelmProtocolTxFacadeTest.this.chartService)
          .lockChart(REPO_ID, "payments");
      order
          .verify(AbstractHelmProtocolTxFacadeTest.this.chartService)
          .findByRepoIdAndNameAndVersion(REPO_ID, "payments", "1.0.0");
      order
          .verify(AbstractHelmProtocolTxFacadeTest.this.ociManifestService)
          .findAllByChartId(chartId);
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

  @Nested
  @DisplayName("listTags() (RPS-1219)")
  class ListTags {

    @Test
    @DisplayName("excludes digest references and returns the rest lexically sorted")
    void filtersDigestReferencesAndSortsLexically() {
      final var it = AbstractHelmProtocolTxFacadeTest.this;
      final var digestReference = "sha256:" + "a".repeat(64);
      when(it.ociManifestService.listTagsByName(REPO_ID, "payments"))
          .thenReturn(List.of("1.0.0", digestReference, "0.9.0"));

      final var result = it.facade.listTags(it.context, "payments");

      assertThat(result.getName()).isEqualTo("payments");
      assertThat(result.getTags()).containsExactly("0.9.0", "1.0.0");
    }

    @Test
    @DisplayName("answers an empty tag list, with the name still populated, for a chart with none")
    void emptyForAChartWithNoManifests() {
      final var it = AbstractHelmProtocolTxFacadeTest.this;
      when(it.ociManifestService.listTagsByName(REPO_ID, "payments")).thenReturn(List.of());

      final var result = it.facade.listTags(it.context, "payments");

      assertThat(result.getName()).isEqualTo("payments");
      assertThat(result.getTags()).isEmpty();
    }
  }
}
