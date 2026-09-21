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

import static io.repsy.protocols.cargo.protocol.handlers.CargoHandlerTestSupport.errorDetail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.cargo.protocol.handlers.AbstractCargoMeProtocolMethodHandler.CargoAuthenticator;
import io.repsy.protocols.shared.exceptions.TooManyRequestsException;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractCargoMeProtocolMethodHandler")
class AbstractCargoMeProtocolMethodHandlerTest {

  private static final String AUTH_HEADER = "Basic dXNlcjpwYXNz";
  private static final String WWW_AUTHENTICATE_VALUE = "Basic realm=\"Repsy Managed Repository\"";

  @Mock private CargoAuthenticator authenticator;
  @Mock private CargoProtocolProvider provider;

  private final ProtocolContext meContext = new ProtocolContext();
  private RelativePath requestedPath;

  private AbstractCargoMeProtocolMethodHandler handler;

  @BeforeEach
  void setUp() {
    handler =
        new AbstractCargoMeProtocolMethodHandler(authenticator, provider) {
          @Override
          protected Optional<ProtocolContext> getProtocolContext(final RelativePath relativePath) {
            requestedPath = relativePath;
            return Optional.of(meContext);
          }
        };
  }

  @Test
  @DisplayName("registers itself with the provider and exposes metadata")
  void metadata() {
    verify(provider).registerMethodHandler(handler);
    assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.GET, HttpMethod.HEAD);
    assertThat(handler.getProperties())
        .containsEntry("permission", Permission.NONE)
        .containsEntry("skipHeaderPreProcessor", true)
        .containsEntry("skipUsagePostProcessor", true)
        .containsEntry("skipPreProcessor", true);
  }

  @Nested
  @DisplayName("getPathParser()")
  class PathParserTests {

    @ParameterizedTest(name = "matches {0} /me")
    @ValueSource(strings = {"GET", "HEAD"})
    @DisplayName("matches GET and HEAD /me using the subclass-provided context")
    void matchesMe(final String method) {
      final var request = new MockHttpServletRequest(method, "/me");
      request.setServletPath("/me");

      assertThat(handler.getPathParser().parse(request)).containsSame(meContext);
      assertThat(requestedPath.getPath()).isEqualTo("/me");
    }

    @Test
    @DisplayName("returns empty for unsupported methods")
    void returnsEmptyForPost() {
      final var request = new MockHttpServletRequest("POST", "/me");
      request.setServletPath("/me");

      assertThat(handler.getPathParser().parse(request)).isEmpty();
    }

    @Test
    @DisplayName("returns empty for paths other than /me")
    void returnsEmptyForOtherPath() {
      final var request = new MockHttpServletRequest("GET", "/cargo/config.json");
      request.setServletPath("/cargo/config.json");

      assertThat(handler.getPathParser().parse(request)).isEmpty();
    }
  }

  @Nested
  @DisplayName("handle()")
  class HandleTests {

    @Test
    @DisplayName("returns 401 with a WWW-Authenticate challenge when no Authorization header")
    void challengesWithoutAuthHeader() {
      final var result =
          handler.handle(meContext, new MockHttpServletRequest(), new MockHttpServletResponse());

      assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
      assertThat(result.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
          .isEqualTo(WWW_AUTHENTICATE_VALUE);
      verifyNoInteractions(authenticator);
    }

    @Test
    @DisplayName("returns the created token for valid credentials")
    void returnsToken() {
      final var request = new MockHttpServletRequest();
      request.addHeader(HttpHeaders.AUTHORIZATION, AUTH_HEADER);
      when(authenticator.authenticateAndCreateToken(AUTH_HEADER)).thenReturn("tok-123");

      final var result = handler.handle(meContext, request, new MockHttpServletResponse());

      assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(result.getBody()).isEqualTo(Map.of("token", "tok-123"));
    }

    @Test
    @DisplayName("returns 401 with a cargo error body when authentication fails")
    void returnsUnauthorizedOnFailure() {
      final var request = new MockHttpServletRequest();
      request.addHeader(HttpHeaders.AUTHORIZATION, AUTH_HEADER);
      when(authenticator.authenticateAndCreateToken(AUTH_HEADER))
          .thenThrow(new IllegalArgumentException("badCredentials"));

      final var result = handler.handle(meContext, request, new MockHttpServletResponse());

      assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
      assertThat(result.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
          .isEqualTo(WWW_AUTHENTICATE_VALUE);
      assertThat(errorDetail(result)).isEqualTo("badCredentials");
    }

    @Test
    @DisplayName(
        "lets a client that is over the failed-login limit through as an exception, not 401")
    void rethrowsTooManyRequests() {
      final var request = new MockHttpServletRequest();
      request.addHeader(HttpHeaders.AUTHORIZATION, AUTH_HEADER);
      when(authenticator.authenticateAndCreateToken(AUTH_HEADER))
          .thenThrow(new TooManyRequestsException(42));

      assertThatThrownBy(() -> handler.handle(meContext, request, new MockHttpServletResponse()))
          .isInstanceOfSatisfying(
              TooManyRequestsException.class,
              ex -> assertThat(ex.getRetryAfterSeconds()).isEqualTo(42));
    }
  }
}
