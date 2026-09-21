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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.helm.protocol.facades.HelmFacade;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractHelmOciBlobUploadStartProtocolMethodHandler")
class AbstractHelmOciBlobUploadStartProtocolMethodHandlerTest {

  private static final UUID UPLOAD_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
  private static final String START_URI = "/v2/charts/app/blobs/uploads";

  @Mock private PathParser basePathParser;
  @Mock private HelmFacade<UUID> helmFacade;
  @Mock private HelmProtocolProvider provider;

  private static class TestHandler
      extends AbstractHelmOciBlobUploadStartProtocolMethodHandler<UUID> {

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
            .relativePath(new RelativePath("/app/blobs/uploads/"))
            .repoInfo(repoInfo)
            .build());
    return context;
  }

  private MockHttpServletResponse start(final String requestUri) {
    final var context = context();
    when(this.helmFacade.startBlobUpload(context)).thenReturn(UPLOAD_ID);
    final var request = new MockHttpServletRequest("POST", requestUri);
    final var response = new MockHttpServletResponse();

    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    try {
      final var entity =
          new TestHandler(this.basePathParser, this.helmFacade, this.provider)
              .handle(context, request, response);
      assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
      assertThat(entity.getHeaders().getFirst("Docker-Upload-UUID"))
          .isEqualTo(UPLOAD_ID.toString());
      response.setHeader("Location", entity.getHeaders().getFirst("Location"));
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }

    return response;
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {START_URI, START_URI + "/", START_URI + "///"})
  @DisplayName("locates the new upload under the request path, with no slash doubled")
  void locatesTheUploadUnderTheRequestPath(final String requestUri) {
    final var response = this.start(requestUri);

    assertThat(response.getHeader("Location")).endsWith(START_URI + "/" + UPLOAD_ID);
  }

  @Test
  @DisplayName("strips a very long run of trailing slashes in linear time")
  void stripsALongRunOfTrailingSlashes() {
    final var requestUri = START_URI + "/".repeat(50_000);

    final var response =
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> this.start(requestUri));

    assertThat(response.getHeader("Location")).endsWith(START_URI + "/" + UPLOAD_ID);
  }
}
