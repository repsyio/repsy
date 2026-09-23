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
package io.repsy.protocols.docker.protocol.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.docker.protocol.facades.DockerProtocolFacade;
import io.repsy.protocols.docker.shared.layer.dtos.LayerForm;
import io.repsy.protocols.docker.shared.layer.dtos.LayerInfo;
import io.repsy.protocols.docker.shared.layer.services.LayerService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractDockerUploadFinalizeProtocolMethodHandler")
class AbstractDockerUploadFinalizeProtocolMethodHandlerTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String REPO_NAME = "images";
  private static final String SESSION_ID = "00000000-0000-0000-0000-000000000002";
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final String UPLOAD_URI = "/v2/images/app/blobs/uploads/" + SESSION_ID;
  private static final String UPLOAD_PATH_VALUE = "/blobs/" + SESSION_ID;

  @Mock private PathParser basePathParser;
  @Mock private DockerProtocolFacade<UUID> dockerFacade;
  @Mock private LayerService<UUID> layerService;
  @Mock private DockerProtocolProvider provider;

  private ProtocolContext context;

  private static class TestHandler extends AbstractDockerUploadFinalizeProtocolMethodHandler<UUID> {

    TestHandler(
        final PathParser basePathParser,
        final DockerProtocolFacade<UUID> dockerFacade,
        final LayerService<UUID> layerService,
        final DockerProtocolProvider provider) {
      super(basePathParser, dockerFacade, layerService, provider);
    }

    @Override
    protected String getServletURILocation(
        final ProtocolContext context, final String imageName, final String digest) {
      return "/v2/" + REPO_NAME + "/" + imageName + "/blobs/" + digest;
    }
  }

  @BeforeEach
  void setUp() {
    final var repoInfo = new BaseRepoInfo<UUID>();
    repoInfo.setId(REPO_ID);
    repoInfo.setStorageKey(REPO_ID);
    repoInfo.setName(REPO_NAME);

    this.context = new ProtocolContext();
    this.context.addProperty(
        "urlProperties",
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName(REPO_NAME)
            .relativePath(new RelativePath("/app/blobs/uploads/" + SESSION_ID))
            .repoInfo(repoInfo)
            .build());
  }

  private static RelativePath uploadPath() {
    return argThat(path -> path != null && path.getPath().equals(UPLOAD_PATH_VALUE));
  }

  private TestHandler handler() {
    return new TestHandler(
        this.basePathParser, this.dockerFacade, this.layerService, this.provider);
  }

  private static MockHttpServletRequest request(final byte[] body) {
    final var request = new MockHttpServletRequest("PUT", UPLOAD_URI);
    request.setParameter("digest", DIGEST);
    request.setContent(body);
    return request;
  }

  @Test
  @DisplayName("appends the closing chunk, verifies the digest, then records the layer")
  void verifiesTheDigestBeforeRecordingTheLayer() throws Exception {
    final var layerInfo = LayerInfo.builder().uuid(UUID.randomUUID()).digest(DIGEST).build();
    when(this.layerService.findOrCreate(any(LayerForm.class), eq(REPO_ID))).thenReturn(layerInfo);

    final var response =
        this.handler().handle(this.context, request(new byte[16]), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getHeaders().getFirst("Docker-Upload-UUID")).isEqualTo(SESSION_ID);
    final var order = inOrder(this.dockerFacade, this.layerService);
    order
        .verify(this.dockerFacade)
        .uploadLayerChunk(eq(this.context), uploadPath(), any(), eq(16L));
    order.verify(this.dockerFacade).verifyLayerDigest(eq(this.context), uploadPath(), eq(DIGEST));
    order.verify(this.layerService).findOrCreate(any(LayerForm.class), eq(REPO_ID));
    order
        .verify(this.dockerFacade)
        .finalizeLayerUpload(eq(this.context), uploadPath(), eq(layerInfo));
  }

  @Test
  @DisplayName("sends no closing chunk when the finalize request has no body")
  void skipsTheChunkOfAnEmptyBody() throws Exception {
    when(this.layerService.findOrCreate(any(LayerForm.class), eq(REPO_ID)))
        .thenReturn(LayerInfo.builder().uuid(UUID.randomUUID()).digest(DIGEST).build());

    this.handler().handle(this.context, request(new byte[0]), new MockHttpServletResponse());

    verify(this.dockerFacade, never()).uploadLayerChunk(any(), any(), any(), any(Long.class));
    verify(this.dockerFacade).verifyLayerDigest(eq(this.context), uploadPath(), eq(DIGEST));
  }

  @Test
  @DisplayName("records no layer and stores no blob when the digest does not match")
  void refusesADigestMismatch() throws Exception {
    doThrow(new BadRequestException("digestMismatch"))
        .when(this.dockerFacade)
        .verifyLayerDigest(eq(this.context), uploadPath(), eq(DIGEST));

    assertThatThrownBy(
            () ->
                this.handler()
                    .handle(this.context, request(new byte[0]), new MockHttpServletResponse()))
        .isInstanceOf(BadRequestException.class);

    verify(this.layerService, never()).findOrCreate(any(), any());
    verify(this.dockerFacade, never()).finalizeLayerUpload(any(), any(), any());
  }
}
