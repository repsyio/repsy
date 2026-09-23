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
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.helm.protocol.facades.HelmFacade;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciTagListDto;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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

/** RPS-1219: the missing {@code GET .../tags/list} route. */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractHelmOciTagsListProtocolMethodHandler")
class AbstractHelmOciTagsListProtocolMethodHandlerTest {

  private static final UUID REPO_ID = UUID.randomUUID();

  @Mock private PathParser basePathParser;
  @Mock private HelmFacade<UUID> facade;
  @Mock private HelmProtocolProvider provider;

  private AbstractHelmOciTagsListProtocolMethodHandler<UUID> handler;

  private static class TestHandler extends AbstractHelmOciTagsListProtocolMethodHandler<UUID> {
    TestHandler(final PathParser p, final HelmFacade<UUID> f, final HelmProtocolProvider pr) {
      super(p, f, pr);
    }
  }

  @BeforeEach
  void setUp() {
    this.handler = new TestHandler(this.basePathParser, this.facade, this.provider);
  }

  private static ProtocolContext context(final String relativePath) {
    final var repoInfo =
        BaseRepoInfo.<UUID>builder().id(REPO_ID).storageKey(REPO_ID).name("helm").build();
    final var urlProps =
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName("helm")
            .relativePath(new RelativePath(relativePath))
            .repoInfo(repoInfo)
            .build();
    final var context = new ProtocolContext();
    context.addProperty("urlProperties", urlProps);
    return context;
  }

  @Test
  @DisplayName("path parser accepts GET .../tags/list")
  void pathParserAcceptsGetTagsList() {
    final var context = context("/payments/tags/list");
    final var request = new MockHttpServletRequest("GET", "/v2/helm/payments/tags/list");
    when(this.basePathParser.parse(request)).thenReturn(Optional.of(context));

    final var parsed = this.handler.getPathParser().parse(request);

    assertThat(parsed).isPresent();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"POST", "PUT", "DELETE"})
  @DisplayName("path parser rejects a non-GET method of the same path")
  void pathParserRejectsNonGet(final String method) {
    final var request = new MockHttpServletRequest(method, "/v2/helm/payments/tags/list");

    final var parsed = this.handler.getPathParser().parse(request);

    assertThat(parsed).isEmpty();
  }

  @Test
  @DisplayName("path parser rejects a manifests path")
  void pathParserRejectsManifestsPath() {
    final var context = context("/payments/manifests/1.0.0");
    final var request = new MockHttpServletRequest("GET", "/v2/helm/payments/manifests/1.0.0");
    when(this.basePathParser.parse(request)).thenReturn(Optional.of(context));

    final var parsed = this.handler.getPathParser().parse(request);

    assertThat(parsed).isEmpty();
  }

  @Test
  @DisplayName("path parser rejects extra path segments after tags/list")
  void pathParserRejectsTrailingSegment() {
    final var context = context("/payments/tags/list/extra");
    final var request = new MockHttpServletRequest("GET", "/v2/helm/payments/tags/list/extra");
    when(this.basePathParser.parse(request)).thenReturn(Optional.of(context));

    final var parsed = this.handler.getPathParser().parse(request);

    assertThat(parsed).isEmpty();
  }

  @Test
  @DisplayName("handle() returns 200 with the facade's tag list, name and all")
  void handleReturnsFacadeTagList() throws Exception {
    final var context = context("/payments/tags/list");
    final var dto =
        HelmOciTagListDto.builder().name("payments").tags(List.of("0.9.0", "1.0.0")).build();
    when(this.facade.listTags(context, "payments")).thenReturn(dto);

    final var response =
        this.handler.handle(
            context,
            new MockHttpServletRequest("GET", "/v2/helm/payments/tags/list"),
            new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(dto);
  }

  @Test
  @DisplayName("handle() answers 500 when the context's relative path is not its own route")
  void handleGuardsAgainstAMismatchedPath() throws Exception {
    final var context = context("/payments/manifests/1.0.0");

    final var response =
        this.handler.handle(
            context,
            new MockHttpServletRequest("GET", "/v2/helm/payments/manifests/1.0.0"),
            new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }
}
