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
package io.repsy.protocols.helm.protocol.handlers.classic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.helm.protocol.facades.HelmProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractHelmChartPullProtocolMethodHandler")
class AbstractHelmChartPullProtocolMethodHandlerTest {

  @Mock private PathParser basePathParser;
  @Mock private HelmProtocolFacade<UUID> helmFacade;
  @Mock private HelmProtocolProvider provider;

  private static class TestHandler extends AbstractHelmChartPullProtocolMethodHandler<UUID> {

    TestHandler(
        final PathParser basePathParser,
        final HelmProtocolFacade<UUID> helmFacade,
        final HelmProtocolProvider provider) {
      super(basePathParser, helmFacade, provider);
    }
  }

  private static ProtocolContext context(final String relativePath) {
    final var repoInfo = new BaseRepoInfo<UUID>();
    repoInfo.setName("charts");
    final var context = new ProtocolContext();
    context.addProperty(
        "urlProperties",
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName("charts")
            .relativePath(new RelativePath(relativePath))
            .repoInfo(repoInfo)
            .build());
    return context;
  }

  private String contentDispositionOf(final String filename) throws Exception {
    final var context = context("/charts/" + filename);
    when(this.helmFacade.getChart(any(), any())).thenReturn(new ByteArrayResource(new byte[0]));

    final var response =
        new TestHandler(this.basePathParser, this.helmFacade, this.provider)
            .handle(context, new MockHttpServletRequest(), new MockHttpServletResponse());

    return response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
  }

  @Test
  @DisplayName("names the chart file as an attachment")
  void attachment() throws Exception {
    assertThat(contentDispositionOf("payments-1.0.0.tgz"))
        .isEqualTo("attachment; filename=\"payments-1.0.0.tgz\"");
  }

  @Test
  @DisplayName("escapes a quote in the file name, so it cannot end the header value")
  void escapesQuote() throws Exception {
    assertThat(contentDispositionOf("pay\"ments-1.0.0.tgz"))
        .isEqualTo("attachment; filename=\"pay\\\"ments-1.0.0.tgz\"");
  }
}
