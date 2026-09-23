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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractDockerUploadStatusProtocolMethodHandler")
class AbstractDockerUploadStatusProtocolMethodHandlerTest {

  private static final String REPO_NAME = "images";
  private static final String SESSION_ID = "00000000-0000-0000-0000-000000000002";
  private static final String UPLOAD_URI = "/v2/images/app/blobs/uploads/" + SESSION_ID;

  @Mock private PathParser basePathParser;
  @Mock private DockerProtocolFacade<UUID> dockerFacade;
  @Mock private DockerProtocolProvider provider;

  private static class TestHandler extends AbstractDockerUploadStatusProtocolMethodHandler<UUID> {

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

  private TestHandler handler() {
    return new TestHandler(this.basePathParser, this.dockerFacade, this.provider);
  }

  @Test
  @DisplayName("answers 204 with the running Range and the real session id when the upload exists")
  void answers204WithTheRunningRange() throws Exception {
    final var context = context();
    when(this.dockerFacade.getUploadSize(
            eq(context),
            argThat(path -> path != null && path.getPath().equals("/blobs/" + SESSION_ID))))
        .thenReturn(350L);

    final var response =
        this.handler()
            .handle(
                context,
                new MockHttpServletRequest("GET", UPLOAD_URI),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(response.getHeaders().getFirst("Range")).isEqualTo("0-349");
    assertThat(response.getHeaders().getFirst("Docker-Upload-UUID")).isEqualTo(SESSION_ID);
    assertThat(response.getHeaders().getFirst("Location"))
        .isEqualTo("/v2/images/app/blobs/uploads/" + SESSION_ID);
  }

  @Test
  @DisplayName("answers a well-formed range for an upload with no bytes yet")
  void answersAWellFormedRangeForAnEmptyUpload() throws Exception {
    final var context = context();
    when(this.dockerFacade.getUploadSize(
            eq(context),
            argThat(path -> path != null && path.getPath().equals("/blobs/" + SESSION_ID))))
        .thenReturn(0L);

    final var response =
        this.handler()
            .handle(
                context,
                new MockHttpServletRequest("HEAD", UPLOAD_URI),
                new MockHttpServletResponse());

    assertThat(response.getHeaders().getFirst("Range")).isEqualTo("0-0");
  }

  @Test
  @DisplayName("propagates the not-found failure of an unknown upload session")
  void propagatesTheNotFoundFailureOfAnUnknownSession() throws Exception {
    final var context = context();
    when(this.dockerFacade.getUploadSize(
            eq(context),
            argThat(path -> path != null && path.getPath().equals("/blobs/" + SESSION_ID))))
        .thenThrow(new ItemNotFoundException("resourceNotFound"));

    assertThatThrownBy(
            () ->
                this.handler()
                    .handle(
                        context,
                        new MockHttpServletRequest("GET", UPLOAD_URI),
                        new MockHttpServletResponse()))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("resourceNotFound");
  }
}
