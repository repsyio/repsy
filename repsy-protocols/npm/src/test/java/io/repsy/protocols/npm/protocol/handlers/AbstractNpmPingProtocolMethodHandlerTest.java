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
package io.repsy.protocols.npm.protocol.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.npm.protocol.handlers.NpmHandlerTestSupport.FixedBaseParser;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNpmPingProtocolMethodHandler")
class AbstractNpmPingProtocolMethodHandlerTest {

  @Mock private NpmProtocolProvider provider;

  private static class TestHandler extends AbstractNpmPingProtocolMethodHandler {
    TestHandler(final io.repsy.libs.protocol.router.PathParser base, final NpmProtocolProvider p) {
      super(base, p);
    }
  }

  private TestHandler handler() {
    return new TestHandler(new FixedBaseParser("/-/ping", true), this.provider);
  }

  @Test
  @DisplayName("registers for GET and reads; writeOperation is present and false")
  void metadata() {
    final var handler = this.handler();

    verify(this.provider).registerMethodHandler(handler);
    assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.GET);
    assertThat(handler.getProperties())
        .containsEntry("permission", Permission.READ)
        .containsEntry("writeOperation", false)
        .containsEntry("skipUsagePostProcessor", true)
        .doesNotContainKey("requireAuthentication");
  }

  @Test
  @DisplayName("claims GET /-/ping and nothing else")
  void parser() {
    final var parser = this.handler().getPathParser();

    assertThat(parser.parse(NpmHandlerTestSupport.request("GET", "/npm/-/ping"))).isPresent();
    assertThat(parser.parse(NpmHandlerTestSupport.request("GET", "/npm/-/ping/x"))).isEmpty();
    assertThat(parser.parse(NpmHandlerTestSupport.request("GET", "/npm/ping"))).isEmpty();
  }

  @Test
  @DisplayName("answers an empty JSON object")
  void answersAnEmptyObject() {
    final var response =
        this.handler()
            .handle(
                NpmHandlerTestSupport.context("/-/ping"),
                NpmHandlerTestSupport.request("GET", "/npm/-/ping"),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(response.getBody()).isEqualTo(Map.of());
  }
}
