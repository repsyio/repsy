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
package io.repsy.protocols.nuget.protocol.handlers;

import static io.repsy.protocols.nuget.NuGetTestContexts.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.protocols.nuget.protocol.NuGetProtocolProvider;
import io.repsy.protocols.nuget.protocol.facades.contract.NuGetProtocolFacade;
import io.repsy.protocols.nuget.shared.utils.NuGetBaseUrlResolver;
import io.repsy.protocols.nuget.shared.utils.NuGetUrlBuilder;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNuGetServiceIndexProtocolMethodHandler")
class AbstractNuGetServiceIndexProtocolMethodHandlerTest {

  @Mock private PathParser basePathParser;
  @Mock private NuGetProtocolFacade facade;
  @Mock private NuGetProtocolProvider provider;

  private AbstractNuGetServiceIndexProtocolMethodHandler handler;

  @BeforeEach
  void setUp() {
    handler = new TestHandler(basePathParser, facade, provider);
  }

  static class TestHandler extends AbstractNuGetServiceIndexProtocolMethodHandler {

    TestHandler(final PathParser p, final NuGetProtocolFacade f, final NuGetProtocolProvider pr) {
      this(p, f, pr, NuGetUrlBuilder::buildBaseUrl);
    }

    TestHandler(
        final PathParser p,
        final NuGetProtocolFacade f,
        final NuGetProtocolProvider pr,
        final NuGetBaseUrlResolver resolver) {
      super(p, f, pr, resolver);
    }
  }

  @Test
  @DisplayName("registers itself with the provider and exposes anonymous READ metadata")
  void metadata() {
    verify(provider).registerMethodHandler(handler);
    assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.GET);
    assertThat(handler.getProperties())
        .containsEntry("permission", Permission.READ)
        .containsEntry("writeOperation", false)
        .containsEntry("skipPreProcessor", true);
  }

  @Test
  @DisplayName("getPathParser() returns empty for other HTTP methods")
  void pathParserRejectsOtherMethods() {
    final var request = new MockHttpServletRequest("POST", "/nuget/v3/index.json");

    assertThat(handler.getPathParser().parse(request)).isEmpty();
    verifyNoInteractions(basePathParser);
  }

  @ParameterizedTest(name = "{0} -> matches={1}")
  @CsvSource({
    "/nuget/v3/index.json,      true",
    "/nuget/V3/INDEX.JSON,      true",
    "/nuget/v3/index.json.bak,  false",
    "/nuget/v3/registration,    false"
  })
  @DisplayName("getPathParser() matches .../v3/index.json regardless of case")
  void pathParserMatchesServiceIndex(final String uri, final boolean matches) {
    final var request = new MockHttpServletRequest("GET", uri);
    final var ctx = context(uri);
    if (matches) {
      when(basePathParser.parse(request)).thenReturn(Optional.of(ctx));
    }

    final var result = handler.getPathParser().parse(request);

    if (matches) {
      assertThat(result).containsSame(ctx);
    } else {
      assertThat(result).isEmpty();
    }
  }

  @Test
  @DisplayName(
      "names the resources with the address the resolver gives, not the request's (RPS-1432)")
  void usesTheResolvedBaseUrl() {
    final var resolving =
        new TestHandler(
            basePathParser,
            facade,
            provider,
            (request, repoName) -> "https://repo.example.com/prefix/" + repoName);
    final var ctx = context("/v3/index.json");
    final var request = new MockHttpServletRequest("GET", "/nuget/v3/index.json");

    final var response = resolving.handle(ctx, request, new MockHttpServletResponse());

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    verify(facade).getServiceIndex(ctx, "https://repo.example.com/prefix/nuget");
  }

  @Test
  @DisplayName("falls back to the request when the resolver is the request-derived one")
  void requestDerivedBaseUrl() {
    final var ctx = context("/v3/index.json");
    final var request = new MockHttpServletRequest("GET", "/nuget/v3/index.json");
    request.setServerName("internal");
    request.setServerPort(9090);

    handler.handle(ctx, request, new MockHttpServletResponse());

    verify(facade).getServiceIndex(ctx, "http://internal:9090/nuget");
  }
}
