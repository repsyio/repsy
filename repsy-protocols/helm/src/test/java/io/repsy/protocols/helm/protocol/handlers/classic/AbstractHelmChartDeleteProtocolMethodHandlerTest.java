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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.helm.protocol.facades.HelmFacade;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * RPS-1424: deleting a chart version through {@code DELETE /api/charts/<name>/<version>} removes
 * stored files, so it needs MANAGE like the panel's delete.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractHelmChartDeleteProtocolMethodHandler (RPS-1424)")
class AbstractHelmChartDeleteProtocolMethodHandlerTest {

  @Mock private PathParser basePathParser;
  @Mock private HelmFacade<UUID> helmFacade;
  @Mock private HelmProtocolProvider provider;

  private static class TestHandler extends AbstractHelmChartDeleteProtocolMethodHandler<UUID> {

    TestHandler(
        final PathParser basePathParser,
        final HelmFacade<UUID> helmFacade,
        final HelmProtocolProvider provider) {
      super(basePathParser, helmFacade, provider);
    }
  }

  private TestHandler handler() {
    return new TestHandler(this.basePathParser, this.helmFacade, this.provider);
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

  private Optional<ProtocolContext> parse(final String method, final String relativePath) {
    final var request = new MockHttpServletRequest(method, "/charts" + relativePath);
    when(this.basePathParser.parse(request)).thenReturn(Optional.of(context(relativePath)));

    return this.handler().getPathParser().parse(request);
  }

  @Test
  @DisplayName("needs MANAGE, so a USER account and a deploy token are refused")
  void needsManage() {
    assertThat(this.handler().getProperties())
        .containsEntry("permission", Permission.MANAGE)
        .containsEntry("writeOperation", true);
    assertThat(this.handler().getSupportedMethods()).containsExactly(HttpMethod.DELETE);
  }

  @ParameterizedTest(name = "DELETE {0} -> matches={1}")
  @CsvSource({
    "/api/charts/payments/1.0.0,      true",
    "/api/charts/payments,            false",
    "/api/charts/payments/1.0.0/x,    false",
    "/charts/payments-1.0.0.tgz,      false"
  })
  @DisplayName("getPathParser() takes only the delete of one chart version")
  void pathParser(final String relativePath, final boolean matches) {
    assertThat(this.parse("DELETE", relativePath).isPresent()).isEqualTo(matches);
  }

  @Test
  @DisplayName("getPathParser() ignores the other methods")
  void pathParserIgnoresOtherMethods() {
    final var request = new MockHttpServletRequest("GET", "/charts/api/charts/payments/1.0.0");

    assertThat(this.handler().getPathParser().parse(request)).isEmpty();
  }

  @Test
  @DisplayName("handle() deletes the chart version the path names")
  void handleDeletes() throws Exception {
    final var request = new MockHttpServletRequest("DELETE", "/charts/api/charts/payments/1.0.0");
    final var context = context("/api/charts/payments/1.0.0");

    final var result = this.handler().handle(context, request, new MockHttpServletResponse());

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(this.helmFacade).deleteChart(context, "payments", "1.0.0");
  }

  @Test
  @DisplayName("handle() refuses a path the parser would not have matched")
  void handleRefusesAnotherPath() throws Exception {
    final var request = new MockHttpServletRequest("DELETE", "/charts/api/charts/payments");

    final var result =
        this.handler()
            .handle(context("/api/charts/payments"), request, new MockHttpServletResponse());

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    verifyNoInteractions(this.helmFacade);
  }
}
