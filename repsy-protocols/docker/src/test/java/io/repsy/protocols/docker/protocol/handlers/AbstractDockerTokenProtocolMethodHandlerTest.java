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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.docker.protocol.parser.DockerScopeParser;
import io.repsy.protocols.docker.shared.auth.services.DockerAuthService;
import io.repsy.protocols.shared.auth.dtos.LoginResponse;
import io.repsy.protocols.shared.exceptions.TooManyRequestsException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** RPS-1092: {@code /v2/token} answers a failed login with 401, and a throttled one with 429. */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractDockerTokenProtocolMethodHandler")
class AbstractDockerTokenProtocolMethodHandlerTest {

  private static final String AUTH_HEADER = "Basic dXNlcjpwYXNz";

  @Mock private DockerAuthService<UUID> authService;
  @Mock private DockerScopeParser<UUID> scopeParser;
  @Mock private DockerProtocolProvider provider;

  private AbstractDockerTokenProtocolMethodHandler<UUID> handler;

  @BeforeEach
  void setUp() {
    this.handler =
        new AbstractDockerTokenProtocolMethodHandler<>(
            this.authService, this.scopeParser, this.provider) {
          @Override
          protected Optional<ProtocolContext> getProtocolContext(final RelativePath relativePath) {
            return Optional.of(new ProtocolContext());
          }
        };
  }

  private MockHttpServletRequest tokenRequest() {
    final var request = new MockHttpServletRequest("GET", "/v2/token");
    request.addHeader(HttpHeaders.AUTHORIZATION, AUTH_HEADER);

    return request;
  }

  @Test
  @DisplayName("registers itself with the provider")
  void registers() {
    verify(this.provider).registerMethodHandler(this.handler);
  }

  @Test
  @DisplayName("hands out a token for valid credentials")
  void issuesAToken() throws Exception {
    when(this.authService.authenticateUserDockerCli(AUTH_HEADER)).thenReturn("tok-123");

    final var result =
        this.handler.handle(
            new ProtocolContext(), this.tokenRequest(), new MockHttpServletResponse());

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(result.getBody()).isInstanceOf(LoginResponse.class);
    assertThat(((LoginResponse) result.getBody()).getToken()).isEqualTo("tok-123");
  }

  @Test
  @DisplayName("answers 401 with a challenge when the credentials are wrong")
  void wrongCredentials() throws Exception {
    when(this.authService.authenticateUserDockerCli(AUTH_HEADER))
        .thenThrow(new UnAuthorizedException("unAuthorized"));

    final var result =
        this.handler.handle(
            new ProtocolContext(), this.tokenRequest(), new MockHttpServletResponse());

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(result.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
        .startsWith("Basic realm=");
  }

  @Test
  @DisplayName("lets a client over the failed-login limit through as an exception, not 401")
  void tooManyRequests() {
    when(this.authService.authenticateUserDockerCli(AUTH_HEADER))
        .thenThrow(new TooManyRequestsException(42));

    assertThatThrownBy(
            () ->
                this.handler.handle(
                    new ProtocolContext(), this.tokenRequest(), new MockHttpServletResponse()))
        .isInstanceOfSatisfying(
            TooManyRequestsException.class,
            ex -> assertThat(ex.getRetryAfterSeconds()).isEqualTo(42));
  }
}
