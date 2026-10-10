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
package io.repsy.protocols.cargo.protocol.handlers;

import static io.repsy.protocols.cargo.protocol.handlers.CargoHandlerTestSupport.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
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
@DisplayName("The HEAD of the Cargo config handler (RPS-1465, RPS-2059)")
class CargoConfigHeadTest {

  @Mock private PathParser basePathParser;
  @Mock private CargoProtocolProvider provider;

  static class TestHandler extends AbstractCargoConfigProtocolMethodHandler {

    TestHandler(final PathParser p, final CargoProtocolProvider pr) {
      super(p, pr);
    }
  }

  @Test
  @DisplayName("registers itself, supports only HEAD and needs no credentials like the GET")
  void metadata() {
    final var handler = new TestHandler(basePathParser, provider);

    verify(provider).registerMethodHandler(handler);
    assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.GET);
    assertThat(handler.answersHead()).isTrue();
    assertThat(handler.getHeadProperties())
        .isEqualTo(
            Map.of(
                "permission",
                Permission.READ,
                "skipPreProcessor",
                true,
                "skipUsagePostProcessor",
                true));
  }

  @Test
  @DisplayName("path parser: only config.json goes through the base parser")
  void pathParser() {
    final var handler = new TestHandler(basePathParser, provider);
    final var request = new MockHttpServletRequest("HEAD", "/cargo/config.json");
    request.setServletPath("/cargo/config.json");
    final var ctx = context("/config.json");
    when(basePathParser.parse(request)).thenReturn(Optional.of(ctx));

    assertThat(handler.getPathParser().parse(request)).containsSame(ctx);

    final var other = new MockHttpServletRequest("HEAD", "/cargo/1/a");
    other.setServletPath("/cargo/1/a");
    assertThat(handler.getPathParser().parse(other)).isEmpty();
  }

  @Test
  @DisplayName("answers 200 application/json without a body")
  void answers() {
    final var response =
        new TestHandler(basePathParser, provider)
            .handleHead(
                context("/config.json"),
                new MockHttpServletRequest("HEAD", "/cargo/config.json"),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNull();
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
  }
}
