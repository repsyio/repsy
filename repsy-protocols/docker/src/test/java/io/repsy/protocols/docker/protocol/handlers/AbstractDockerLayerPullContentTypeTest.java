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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * RPS-2172: the blob GET answers the layer's stored media type, the {@code Content-Type} the blob
 * HEAD of the same digest reports, not the image config type.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Docker blob pull content type")
class AbstractDockerLayerPullContentTypeTest {

  private static final String DIGEST = "sha256:" + "ab".repeat(32);
  private static final String LAYER = "application/vnd.docker.image.rootfs.diff.tar.gzip";

  @Mock private PathParser basePathParser;
  @Mock private DockerProtocolFacade<UUID> dockerFacade;
  @Mock private DockerProtocolProvider provider;

  private static class PullHandler extends AbstractDockerLayerPullProtocolMethodHandler<UUID> {
    PullHandler(
        final PathParser parser,
        final DockerProtocolFacade<UUID> facade,
        final DockerProtocolProvider provider) {
      super(parser, facade, provider);
    }
  }

  private String pullContentType(final String storedMediaType) throws Exception {
    final var context = new ProtocolContext();
    context.addProperty(
        "urlProperties",
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName("images")
            .relativePath(new RelativePath("/app/blobs/" + DIGEST))
            .repoInfo(new BaseRepoInfo<UUID>())
            .build());
    when(this.dockerFacade.getLayer(any(), eq(DIGEST), any()))
        .thenReturn(new ByteArrayResource("hello".getBytes()));
    when(this.dockerFacade.getLayerMediaType(any(), eq(DIGEST))).thenReturn(storedMediaType);

    final var response =
        new PullHandler(this.basePathParser, this.dockerFacade, this.provider)
            .handle(
                context,
                new MockHttpServletRequest("GET", "/v2/images/app/blobs/" + DIGEST),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

    return response.getHeaders().getFirst("Content-Type");
  }

  @Test
  @DisplayName("the pull answers the stored media type of the layer")
  void answersStoredMediaType() throws Exception {
    assertThat(this.pullContentType(LAYER)).isEqualTo(LAYER);
  }

  @Test
  @DisplayName("a layer without a stored media type is served as application/octet-stream")
  void fallsBackToOctetStream() throws Exception {
    assertThat(this.pullContentType(" ")).isEqualTo("application/octet-stream");
  }
}
