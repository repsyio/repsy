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
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.helm.protocol.facades.HelmFacade;
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
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractHelmOciBlobUploadStatusProtocolMethodHandler")
class AbstractHelmOciBlobUploadStatusProtocolMethodHandlerTest {

  private static final UUID UPLOAD_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final String UPLOAD_URI = "/v2/charts/app/blobs/uploads/" + UPLOAD_ID;

  @Mock private PathParser basePathParser;
  @Mock private HelmFacade<UUID> helmFacade;
  @Mock private HelmProtocolProvider provider;

  private static class TestHandler
      extends AbstractHelmOciBlobUploadStatusProtocolMethodHandler<UUID> {

    TestHandler(
        final PathParser basePathParser,
        final HelmFacade<UUID> helmFacade,
        final HelmProtocolProvider provider) {
      super(basePathParser, helmFacade, provider);
    }
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

  private ResponseEntity<Object> handle(final ProtocolContext context, final String method)
      throws Exception {
    final var request = new MockHttpServletRequest(method, UPLOAD_URI);

    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    try {
      return new TestHandler(this.basePathParser, this.helmFacade, this.provider)
          .handle(context, request, new MockHttpServletResponse());
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
  }

  @Test
  @DisplayName("answers 204 with the running Range and the real session id when the upload exists")
  void answers204WithTheRunningRange() throws Exception {
    final var context = context();
    when(this.helmFacade.getUploadSize(context, UPLOAD_ID)).thenReturn(350L);

    final var response = this.handle(context, "GET");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(response.getHeaders().getFirst("Range")).isEqualTo("0-349");
    assertThat(response.getHeaders().getFirst("Docker-Upload-UUID"))
        .isEqualTo(UPLOAD_ID.toString());
  }

  @Test
  @DisplayName("answers a well-formed range for an upload with no bytes yet, on HEAD too")
  void answersAWellFormedRangeForAnEmptyUpload() throws Exception {
    final var context = context();
    when(this.helmFacade.getUploadSize(context, UPLOAD_ID)).thenReturn(0L);

    final var response = this.handle(context, "HEAD");

    assertThat(response.getHeaders().getFirst("Range")).isEqualTo("0-0");
  }

  @Test
  @DisplayName("propagates the not-found failure of an unknown upload session")
  void propagatesTheNotFoundFailureOfAnUnknownSession() throws Exception {
    final var context = context();
    when(this.helmFacade.getUploadSize(context, UPLOAD_ID))
        .thenThrow(new ItemNotFoundException("blobNotFound"));

    assertThatThrownBy(() -> this.handle(context, "GET"))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("blobNotFound");
  }
}
