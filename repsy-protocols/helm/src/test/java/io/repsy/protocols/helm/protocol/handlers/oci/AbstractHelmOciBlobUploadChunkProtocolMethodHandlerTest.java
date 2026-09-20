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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

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
@DisplayName("AbstractHelmOciBlobUploadChunkProtocolMethodHandler")
class AbstractHelmOciBlobUploadChunkProtocolMethodHandlerTest {

  private static final UUID UPLOAD_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final String UPLOAD_URI = "/v2/charts/app/blobs/uploads/" + UPLOAD_ID;

  @Mock private PathParser basePathParser;
  @Mock private HelmFacade<UUID> helmFacade;
  @Mock private HelmProtocolProvider provider;

  private static class TestHandler
      extends AbstractHelmOciBlobUploadChunkProtocolMethodHandler<UUID> {

    TestHandler(
        final PathParser basePathParser,
        final HelmFacade<UUID> helmFacade,
        final HelmProtocolProvider provider) {
      super(basePathParser, helmFacade, provider);
    }
  }

  private String rangeAfterChunkOfUploadSize(final long uploadSize) throws Exception {
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
    when(this.helmFacade.uploadBlobChunk(eq(context), eq(UPLOAD_ID), any(), eq(64L)))
        .thenReturn(uploadSize);
    final var request = new MockHttpServletRequest("PATCH", UPLOAD_URI);
    request.setContent(new byte[64]);

    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    final ResponseEntity<Object> response;
    try {
      response =
          new TestHandler(this.basePathParser, this.helmFacade, this.provider)
              .handle(context, request, new MockHttpServletResponse());
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    return response.getHeaders().getFirst("Range");
  }

  @Test
  @DisplayName("answers the range of the whole upload, not of the last chunk")
  void answersTheRangeOfTheWholeUpload() throws Exception {
    assertThat(this.rangeAfterChunkOfUploadSize(350)).isEqualTo("0-349");
  }

  @Test
  @DisplayName("answers a range that starts and ends at zero for an upload with no bytes yet")
  void answersAWellFormedRangeForAnEmptyUpload() throws Exception {
    assertThat(this.rangeAfterChunkOfUploadSize(0)).isEqualTo("0-0");
  }
}
