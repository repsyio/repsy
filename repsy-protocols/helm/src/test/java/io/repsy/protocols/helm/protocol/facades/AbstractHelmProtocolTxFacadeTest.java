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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.helm.shared.chart.services.ChartService;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciBlobInfo;
import io.repsy.protocols.helm.shared.oci.services.OciBlobService;
import io.repsy.protocols.helm.shared.oci.services.OciManifestService;
import io.repsy.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
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
  private static final String DIGEST = "sha256:abc";
  private static final int BLOB_SIZE = 300;

  @Mock private HelmStorageService<UUID> helmStorageService;
  @Mock private ChartService<UUID> chartService;
  @Mock private OciBlobService<UUID> ociBlobService;
  @Mock private OciManifestService<UUID> ociManifestService;
  @Mock private HelmOciBlobInfo blobInfo;

  private TestFacade facade;
  private ProtocolContext context;

  private static class TestFacade extends AbstractHelmProtocolTxFacade<UUID> {

    TestFacade(
        final HelmStorageService<UUID> helmStorageService,
        final ChartService<UUID> chartService,
        final OciBlobService<UUID> ociBlobService,
        final OciManifestService<UUID> ociManifestService) {
      super(helmStorageService, chartService, ociBlobService, ociManifestService);
    }
  }

  @BeforeEach
  void setUp() {
    this.facade =
        new TestFacade(
            this.helmStorageService,
            this.chartService,
            this.ociBlobService,
            this.ociManifestService);

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

  private void blobIsStoredUnderDigest() {
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
  }
}
