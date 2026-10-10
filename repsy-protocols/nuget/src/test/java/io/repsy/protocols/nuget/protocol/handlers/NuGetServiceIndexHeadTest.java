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
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.protocols.nuget.protocol.NuGetProtocolProvider;
import io.repsy.protocols.nuget.protocol.facades.contracts.NuGetProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("The HEAD of the NuGet service index handler (RPS-1465, RPS-2059)")
class NuGetServiceIndexHeadTest {

  @Mock private PathParser basePathParser;
  @Mock private NuGetProtocolProvider provider;

  @Mock private NuGetProtocolFacade facade;

  static class TestHandler extends AbstractNuGetServiceIndexProtocolMethodHandler {

    TestHandler(final PathParser p, final NuGetProtocolFacade f, final NuGetProtocolProvider pr) {
      super(p, f, pr, (request, repoName) -> "http://host/" + repoName);
    }
  }

  private static MockHttpServletRequest request(final String path) {
    final var request = new MockHttpServletRequest("HEAD", path);
    request.setServletPath(path);
    return request;
  }

  @Test
  @DisplayName("registers itself, supports only HEAD and needs no credentials like the GET")
  void metadata() {
    final var handler = new TestHandler(this.basePathParser, this.facade, this.provider);

    verify(this.provider).registerMethodHandler(handler);
    assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.GET);
    assertThat(handler.answersHead()).isTrue();
    assertThat(handler.getHeadProperties())
        .isEqualTo(
            Map.of(
                "permission",
                Permission.READ,
                "writeOperation",
                false,
                "skipPreProcessor",
                true,
                "skipUsagePostProcessor",
                true));
  }

  @Test
  @DisplayName("path parser: only /v3/index.json goes through the base parser")
  void pathParser() {
    final var handler = new TestHandler(this.basePathParser, this.facade, this.provider);
    final var request = request("/nuget/v3/index.json");
    final var ctx = context("/v3/index.json");
    when(this.basePathParser.parse(request)).thenReturn(Optional.of(ctx));

    assertThat(handler.getPathParser().parse(request)).containsSame(ctx);
    assertThat(handler.getPathParser().parse(request("/nuget/v3/query"))).isEmpty();
  }

  @Test
  @DisplayName("answers 200 application/json without a body")
  void answers() {
    final var response =
        new TestHandler(this.basePathParser, this.facade, this.provider)
            .handleHead(
                context("/v3/index.json"),
                request("/nuget/v3/index.json"),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNull();
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
  }
}
