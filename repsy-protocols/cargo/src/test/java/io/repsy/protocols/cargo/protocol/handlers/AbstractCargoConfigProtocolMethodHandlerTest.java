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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.cargo.shared.constants.CargoConstants;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractCargoConfigProtocolMethodHandler")
class AbstractCargoConfigProtocolMethodHandlerTest {

  @Mock private PathParser basePathParser;
  @Mock private CargoProtocolProvider provider;

  private AbstractCargoConfigProtocolMethodHandler handler;

  @BeforeEach
  void setUp() {
    handler = new TestHandler(basePathParser, provider);
  }

  @AfterEach
  void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  static class TestHandler extends AbstractCargoConfigProtocolMethodHandler {

    TestHandler(final PathParser p, final CargoProtocolProvider pr) {
      super(p, pr);
    }
  }

  @Test
  @DisplayName("registers itself with the provider")
  void registersWithProvider() {
    verify(provider).registerMethodHandler(handler);
  }

  @Test
  @DisplayName("supports only GET and requires READ permission")
  void metadata() {
    assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.GET);
    assertThat(handler.getProperties())
        .containsEntry("permission", Permission.READ)
        .containsEntry("skipHeaderPreProcessor", true)
        .containsEntry("skipUsagePostProcessor", true)
        .containsEntry("skipPreProcessor", true);
  }

  @Nested
  @DisplayName("getPathParser()")
  class PathParserTests {

    @Test
    @DisplayName("returns empty for non-GET method")
    void returnsEmptyForNonGet() {
      final var request = new MockHttpServletRequest("PUT", "/cargo/config.json");
      request.setServletPath("/cargo/config.json");

      assertThat(handler.getPathParser().parse(request)).isEmpty();
      verifyNoInteractions(basePathParser);
    }

    @Test
    @DisplayName("returns empty when path does not end with /config.json")
    void returnsEmptyForOtherPath() {
      final var request = new MockHttpServletRequest("GET", "/cargo/me");
      request.setServletPath("/cargo/me");

      assertThat(handler.getPathParser().parse(request)).isEmpty();
      verifyNoInteractions(basePathParser);
    }

    @Test
    @DisplayName("delegates to the base parser for GET /config.json")
    void delegatesToBaseParser() {
      final var request = new MockHttpServletRequest("GET", "/cargo/config.json");
      request.setServletPath("/cargo/config.json");
      final var ctx = new ProtocolContext();
      when(basePathParser.parse(request)).thenReturn(Optional.of(ctx));

      assertThat(handler.getPathParser().parse(request)).containsSame(ctx);
    }
  }

  @Nested
  @DisplayName("handle()")
  class HandleTests {

    private MockHttpServletRequest request;

    @BeforeEach
    void setUpRequest() {
      request = new MockHttpServletRequest("GET", "/cargo/config.json");
      request.setServletPath("/cargo/config.json");
      RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    @DisplayName("returns config without auth-required for a public repo")
    void publicRepoConfig() {
      final var result =
          handler.handle(context("/config.json", false), request, new MockHttpServletResponse());

      assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(result.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
          .isEqualTo(MediaType.APPLICATION_JSON_VALUE);
      assertThat((String) result.getBody())
          .contains("\"dl\": \"http://localhost/cargo/api/v1/crates/{crate}/{version}/download\"")
          .contains("\"api\": \"http://localhost/cargo\"")
          .doesNotContain("auth-required");
    }

    @Test
    @DisplayName("returns config with auth-required for a private repo")
    void privateRepoConfig() {
      final var result =
          handler.handle(context("/config.json", true), request, new MockHttpServletResponse());

      assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat((String) result.getBody())
          .contains("\"api\": \"http://localhost/cargo\"")
          .contains("\"auth-required\": true");
    }

    @Test
    @DisplayName("lets a failure propagate to the error advice, marked for the cargo error body")
    void errorResponse() {
      final var response = new MockHttpServletResponse();

      assertThatThrownBy(() -> handler.handle(new ProtocolContext(), request, response))
          .isInstanceOf(NullPointerException.class);
      assertThat(request.getAttribute(CargoConstants.ERROR_BODY_ATTRIBUTE)).isEqualTo(true);
    }

    /**
     * RPS-1515: Tomcat's RemoteIpValve (server.forward-headers-strategy: native) parses the port
     * out of X-Forwarded-Host and discards it, so request.getServerPort() falls back to the
     * scheme's default when the proxy sends no separate X-Forwarded-Port. dl/api must still carry
     * the port that was embedded in the host.
     */
    @Test
    @DisplayName("keeps the port embedded in X-Forwarded-Host when X-Forwarded-Port is absent")
    void keepsEmbeddedForwardedPort() {
      request.setScheme("https");
      request.setServerName("pub.e2e.test");
      request.setServerPort(443);
      request.addHeader("X-Forwarded-Proto", "https");
      request.addHeader("X-Forwarded-Host", "pub.e2e.test:8443");

      final var result =
          handler.handle(context("/config.json", false), request, new MockHttpServletResponse());

      assertThat((String) result.getBody())
          .contains(
              "\"dl\": \"https://pub.e2e.test:8443/cargo/api/v1/crates/{crate}/{version}/download\"")
          .contains("\"api\": \"https://pub.e2e.test:8443/cargo\"");
    }

    @Test
    @DisplayName("keeps the port when a separate X-Forwarded-Port is sent alongside the host")
    void keepsSeparateForwardedPortHeader() {
      request.setScheme("https");
      request.setServerName("pub.e2e.test");
      request.setServerPort(8443);
      request.addHeader("X-Forwarded-Proto", "https");
      request.addHeader("X-Forwarded-Host", "pub.e2e.test");
      request.addHeader("X-Forwarded-Port", "8443");

      final var result =
          handler.handle(context("/config.json", false), request, new MockHttpServletResponse());

      assertThat((String) result.getBody())
          .contains("\"api\": \"https://pub.e2e.test:8443/cargo\"");
    }
  }
}
