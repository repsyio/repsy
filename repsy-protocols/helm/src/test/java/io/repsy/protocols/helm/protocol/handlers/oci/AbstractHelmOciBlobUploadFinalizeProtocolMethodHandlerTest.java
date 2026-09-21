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
package io.repsy.protocols.helm.protocol.handlers.oci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.helm.protocol.facades.HelmFacade;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciBlobInfo;
import io.repsy.protocols.helm.shared.utils.HelmConstants;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * RPS-1072: {@code helm_oci_blob.digest} is {@code varchar(71)}, which holds a sha256 digest but
 * not the sha512 one {@code BlobDigests} also admits, and {@code helm_oci_blob.media_type} is
 * {@code varchar(255)}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractHelmOciBlobUploadFinalizeProtocolMethodHandler")
class AbstractHelmOciBlobUploadFinalizeProtocolMethodHandlerTest {

  private static final UUID UPLOAD_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final String UPLOAD_URI = "/v2/charts/app/blobs/uploads/" + UPLOAD_ID;
  private static final String SHA256 = "sha256:" + "a".repeat(64);
  private static final String SHA512 = "sha512:" + "a".repeat(128);
  private static final String OCTET_STREAM = "application/octet-stream";

  @Mock private PathParser basePathParser;
  @Mock private HelmFacade<UUID> helmFacade;
  @Mock private HelmProtocolProvider provider;
  @Mock private HelmOciBlobInfo blobInfo;

  private static class TestHandler
      extends AbstractHelmOciBlobUploadFinalizeProtocolMethodHandler<UUID> {

    TestHandler(
        final PathParser basePathParser,
        final HelmFacade<UUID> helmFacade,
        final HelmProtocolProvider provider) {
      super(basePathParser, helmFacade, provider);
    }
  }

  @AfterEach
  void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  private static ProtocolContext context() {
    final var repoInfo = new BaseRepoInfo<UUID>();
    repoInfo.setName("charts");
    final var context = new ProtocolContext();
    context.addProperty(
        "urlProperties",
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName("charts")
            .relativePath(new RelativePath("/app/blobs/uploads/" + UPLOAD_ID))
            .repoInfo(repoInfo)
            .build());
    return context;
  }

  private ResponseEntity<Object> finalizeUpload(
      final ProtocolContext context, final String digest, final String contentType)
      throws Exception {
    final var request = new MockHttpServletRequest("PUT", UPLOAD_URI);
    request.setParameter("digest", digest);
    if (contentType != null) {
      request.setContentType(contentType);
    }
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

    return new TestHandler(this.basePathParser, this.helmFacade, this.provider)
        .handle(context, request, new MockHttpServletResponse());
  }

  private String storedMediaType(final String contentType) throws Exception {
    final var context = context();
    when(this.blobInfo.digest()).thenReturn(SHA256);
    when(this.helmFacade.finalizeBlob(
            eq(context), eq(UPLOAD_ID), eq(SHA256), any(), any(), anyLong()))
        .thenReturn(this.blobInfo);

    final var response = this.finalizeUpload(context, SHA256, contentType);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    final var mediaType = ArgumentCaptor.forClass(String.class);
    verify(this.helmFacade)
        .finalizeBlob(
            eq(context), eq(UPLOAD_ID), eq(SHA256), mediaType.capture(), any(), anyLong());
    return mediaType.getValue();
  }

  @Test
  @DisplayName("rejects a sha512 digest before the blob is stored")
  void rejectsSha512() {
    assertThat(SHA512).hasSizeGreaterThan(HelmConstants.MAX_DIGEST_LENGTH);

    assertThatThrownBy(() -> this.finalizeUpload(context(), SHA512, OCTET_STREAM))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("blobDigestUnsupported");

    verifyNoInteractions(this.helmFacade);
  }

  @Test
  @DisplayName("passes a sha256 digest on")
  void acceptsSha256() throws Exception {
    assertThat(SHA256).hasSize(HelmConstants.MAX_DIGEST_LENGTH);

    assertThat(this.storedMediaType(OCTET_STREAM)).isEqualTo(OCTET_STREAM);
  }

  @Test
  @DisplayName("leaves a malformed digest to the digest check, which refuses it")
  void leavesAMalformedDigestToTheDigestCheck() throws Exception {
    final var context = context();
    when(this.helmFacade.finalizeBlob(
            eq(context), eq(UPLOAD_ID), eq("sha512:abc"), any(), any(), anyLong()))
        .thenThrow(new BadRequestException("digestMismatch"));

    assertThatThrownBy(() -> this.finalizeUpload(context, "sha512:abc", OCTET_STREAM))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("digestMismatch");
  }

  @Test
  @DisplayName("records a Content-Type of exactly 255 characters as it is")
  void keepsAMediaTypeAtTheLimit() throws Exception {
    final var mediaType =
        "application/"
            + "x".repeat(HelmConstants.MAX_OCI_MEDIA_TYPE_LENGTH - "application/".length());

    assertThat(this.storedMediaType(mediaType)).isEqualTo(mediaType);
  }

  @Test
  @DisplayName("records the generic type for a Content-Type over 255 characters")
  void fallsBackForAnOverLongMediaType() throws Exception {
    final var mediaType = "application/" + "x".repeat(HelmConstants.MAX_OCI_MEDIA_TYPE_LENGTH);

    assertThat(this.storedMediaType(mediaType)).isEqualTo(OCTET_STREAM);
  }

  @Test
  @DisplayName("records the generic type for a request without a Content-Type")
  void fallsBackForAMissingMediaType() throws Exception {
    assertThat(this.storedMediaType(null)).isEqualTo(OCTET_STREAM);
  }
}
