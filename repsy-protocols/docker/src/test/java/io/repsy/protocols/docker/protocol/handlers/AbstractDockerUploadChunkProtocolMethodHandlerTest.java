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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.docker.protocol.facades.DockerProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractDockerUploadChunkProtocolMethodHandler")
class AbstractDockerUploadChunkProtocolMethodHandlerTest {

  private static final String REPO_NAME = "images";
  private static final String SESSION_ID = "00000000-0000-0000-0000-000000000002";
  private static final String UPLOAD_URI = "/v2/images/app/blobs/uploads/" + SESSION_ID;

  @Mock private PathParser basePathParser;
  @Mock private DockerProtocolFacade<UUID> dockerFacade;
  @Mock private DockerProtocolProvider provider;

  private static class TestHandler extends AbstractDockerUploadChunkProtocolMethodHandler<UUID> {

    TestHandler(
        final PathParser basePathParser,
        final DockerProtocolFacade<UUID> dockerFacade,
        final DockerProtocolProvider provider) {
      super(basePathParser, dockerFacade, provider);
    }

    @Override
    protected String getServletURILocation(
        final ProtocolContext context, final String imageName, final String sessionId) {
      return "/v2/" + REPO_NAME + "/" + imageName + "/blobs/uploads/" + sessionId;
    }
  }

  private static ProtocolContext context() {
    final var repoInfo = new BaseRepoInfo<UUID>();
    repoInfo.setName(REPO_NAME);

    final var context = new ProtocolContext();
    context.addProperty(
        "urlProperties",
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName(REPO_NAME)
            .relativePath(new RelativePath("/app/blobs/uploads/" + SESSION_ID))
            .repoInfo(repoInfo)
            .build());
    return context;
  }

  private ResponseEntity<Object> answerFor(final long uploadSize) throws Exception {
    final var context = context();
    when(this.dockerFacade.uploadLayerChunk(
            eq(context),
            argThat(path -> path != null && path.getPath().equals("/blobs/" + SESSION_ID)),
            any(),
            eq(64L)))
        .thenReturn(uploadSize);
    final var request = new MockHttpServletRequest("PATCH", UPLOAD_URI);
    request.setContent(new byte[64]);

    return new TestHandler(this.basePathParser, this.dockerFacade, this.provider)
        .handle(context, request, new MockHttpServletResponse());
  }

  @Test
  @DisplayName("answers 202 with the range of the whole upload, not of the last chunk")
  void answersTheRangeOfTheWholeUpload() throws Exception {
    final var answer = this.answerFor(350);

    assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(answer.getHeaders().getFirst("Range")).isEqualTo("0-349");
  }

  @Test
  @DisplayName("answers a range that starts and ends at zero for an upload with no bytes yet")
  void answersAWellFormedRangeForAnEmptyUpload() throws Exception {
    assertThat(this.answerFor(0).getHeaders().getFirst("Range")).isEqualTo("0-0");
  }

  @Test
  @DisplayName("echoes the session id of the URL as the Docker-Upload-UUID header")
  void echoesTheSessionIdAsTheUploadUuidHeader() throws Exception {
    assertThat(this.answerFor(64).getHeaders().getFirst("Docker-Upload-UUID"))
        .isEqualTo(SESSION_ID);
  }

  @Test
  @DisplayName("appends the chunk when Content-Range starts where the upload currently ends")
  void appendsWhenContentRangeMatchesTheCurrentSize() throws Exception {
    final var context = context();
    when(this.dockerFacade.getUploadSize(
            eq(context),
            argThat(path -> path != null && path.getPath().equals("/blobs/" + SESSION_ID))))
        .thenReturn(100L);
    when(this.dockerFacade.uploadLayerChunk(
            eq(context),
            argThat(path -> path != null && path.getPath().equals("/blobs/" + SESSION_ID)),
            any(),
            eq(64L)))
        .thenReturn(164L);
    final var request = new MockHttpServletRequest("PATCH", UPLOAD_URI);
    request.setContent(new byte[64]);
    request.addHeader("Content-Range", "100-163");

    final var response =
        new TestHandler(this.basePathParser, this.dockerFacade, this.provider)
            .handle(context, request, new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getHeaders().getFirst("Range")).isEqualTo("0-163");
    verify(this.dockerFacade)
        .uploadLayerChunk(
            eq(context),
            argThat(path -> path != null && path.getPath().equals("/blobs/" + SESSION_ID)),
            any(),
            eq(64L));
  }

  @Test
  @DisplayName("refuses a chunk whose Content-Range does not start at the current upload size")
  void refusesAContentRangeThatDoesNotMatchTheCurrentSize() throws Exception {
    final var context = context();
    when(this.dockerFacade.getUploadSize(
            eq(context),
            argThat(path -> path != null && path.getPath().equals("/blobs/" + SESSION_ID))))
        .thenReturn(100L);
    final var request = new MockHttpServletRequest("PATCH", UPLOAD_URI);
    request.setContent(new byte[64]);
    request.addHeader("Content-Range", "0-63");

    final var response =
        new TestHandler(this.basePathParser, this.dockerFacade, this.provider)
            .handle(context, request, new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
    assertThat(response.getHeaders().getFirst("Range")).isEqualTo("0-99");
    assertThat(response.getHeaders().getFirst("Docker-Upload-UUID")).isEqualTo(SESSION_ID);
    assertThat(response.getHeaders().getFirst("Location")).isNotBlank();
    verify(this.dockerFacade, never()).uploadLayerChunk(any(), any(), any(), any(Long.class));
  }

  @Test
  @DisplayName("treats a fresh upload with no bytes yet as size zero, not a failure")
  void treatsAMissingUploadFileAsSizeZeroForContentRangeChecks() throws Exception {
    final var context = context();
    when(this.dockerFacade.getUploadSize(
            eq(context),
            argThat(path -> path != null && path.getPath().equals("/blobs/" + SESSION_ID))))
        .thenThrow(new ItemNotFoundException("resourceNotFound"));
    when(this.dockerFacade.uploadLayerChunk(
            eq(context),
            argThat(path -> path != null && path.getPath().equals("/blobs/" + SESSION_ID)),
            any(),
            eq(64L)))
        .thenReturn(64L);
    final var request = new MockHttpServletRequest("PATCH", UPLOAD_URI);
    request.setContent(new byte[64]);
    request.addHeader("Content-Range", "0-63");

    final var response =
        new TestHandler(this.basePathParser, this.dockerFacade, this.provider)
            .handle(context, request, new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    verify(this.dockerFacade)
        .uploadLayerChunk(
            eq(context),
            argThat(path -> path != null && path.getPath().equals("/blobs/" + SESSION_ID)),
            any(),
            eq(64L));
  }

  @Test
  @DisplayName("keeps the old append behavior when the request carries no Content-Range")
  void appendsWithoutCheckingWhenContentRangeIsAbsent() throws Exception {
    final var context = context();
    when(this.dockerFacade.uploadLayerChunk(
            eq(context),
            argThat(path -> path != null && path.getPath().equals("/blobs/" + SESSION_ID)),
            any(),
            eq(64L)))
        .thenReturn(64L);
    final var request = new MockHttpServletRequest("PATCH", UPLOAD_URI);
    request.setContent(new byte[64]);

    final var response =
        new TestHandler(this.basePathParser, this.dockerFacade, this.provider)
            .handle(context, request, new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    verify(this.dockerFacade, never()).getUploadSize(any(), any());
  }
}
