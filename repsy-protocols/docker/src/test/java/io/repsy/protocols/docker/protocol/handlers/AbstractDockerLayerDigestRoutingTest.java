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
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.docker.protocol.facades.DockerProtocolFacade;
import io.repsy.protocols.docker.shared.layer.services.LayerService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * RPS-1242: the blob GET and HEAD routes match a digest of any algorithm {@code BlobDigests}
 * supports, not only {@code sha256:}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Docker blob handlers route supported digests")
class AbstractDockerLayerDigestRoutingTest {

  private static final String SHA256 = "sha256:" + "ab".repeat(32);
  private static final String SHA512 = "sha512:" + "cd".repeat(64);

  @Mock private PathParser basePathParser;
  @Mock private DockerProtocolFacade<UUID> dockerFacade;
  @Mock private LayerService<UUID> layerService;
  @Mock private DockerProtocolProvider provider;

  private static class PullHandler extends AbstractDockerLayerPullProtocolMethodHandler<UUID> {
    PullHandler(
        final PathParser parser,
        final DockerProtocolFacade<UUID> facade,
        final DockerProtocolProvider provider) {
      super(parser, facade, provider);
    }
  }

  private static class CheckHandler extends AbstractDockerLayerCheckProtocolMethodHandler<UUID> {
    CheckHandler(
        final PathParser parser,
        final LayerService<UUID> layerService,
        final DockerProtocolProvider provider) {
      super(parser, layerService, provider);
    }
  }

  private void basePathIs(final String relativePath) {
    final var context = new ProtocolContext();
    context.addProperty(
        "urlProperties",
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName("images")
            .relativePath(new RelativePath(relativePath))
            .repoInfo(new BaseRepoInfo<UUID>())
            .build());
    when(this.basePathParser.parse(any())).thenReturn(Optional.of(context));
  }

  private boolean routes(final ProtocolMethodHandler handler, final String method) {
    return handler.getPathParser().parse(new MockHttpServletRequest(method, "/v2/x")).isPresent();
  }

  @ParameterizedTest
  @ValueSource(strings = {SHA256, SHA512})
  @DisplayName("GET of a blob by a supported digest is routed to the pull handler")
  void pullRoutesSupportedDigests(final String digest) {
    this.basePathIs("/app/blobs/" + digest);

    assertThat(
            this.routes(
                new PullHandler(this.basePathParser, this.dockerFacade, this.provider), "GET"))
        .isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {SHA256, SHA512})
  @DisplayName("HEAD of a blob by a supported digest is routed to the check handler")
  void checkRoutesSupportedDigests(final String digest) {
    this.basePathIs("/app/blobs/" + digest);

    assertThat(
            this.routes(
                new CheckHandler(this.basePathParser, this.layerService, this.provider), "HEAD"))
        .isTrue();
  }

  @Test
  @DisplayName("a digest of the wrong length or algorithm is routed to neither handler")
  void malformedDigestsAreNotRouted() {
    for (final var digest :
        new String[] {
          "sha512:" + "cd".repeat(32), "sha256:" + "ab".repeat(64), "sha384:" + "ab".repeat(48)
        }) {
      this.basePathIs("/app/blobs/" + digest);

      assertThat(
              this.routes(
                  new PullHandler(this.basePathParser, this.dockerFacade, this.provider), "GET"))
          .as(digest)
          .isFalse();
      assertThat(
              this.routes(
                  new CheckHandler(this.basePathParser, this.layerService, this.provider), "HEAD"))
          .as(digest)
          .isFalse();
    }
  }
}
