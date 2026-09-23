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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.docker.protocol.parser.DockerScopeParser;
import io.repsy.protocols.docker.shared.auth.services.DockerAuthService;
import io.repsy.protocols.shared.auth.dtos.LoginResponse;
import io.repsy.protocols.shared.exceptions.TooManyRequestsException;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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

/**
 * RPS-1092: {@code /v2/token} answers a failed login with 401, and a throttled one with 429.
 * RPS-1097: a caller without credentials gets an anonymous token for a public repo only.
 */
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

  /**
   * RPS-1165: only a credential failure is answered as 401. Anything else (a database outage, a
   * bug) must reach {@code ErrorHandler} so it is logged and answered 500, instead of looking like
   * a wrong credential to the client.
   */
  @Test
  @DisplayName("lets a non-authentication exception propagate instead of answering 401")
  void nonAuthenticationExceptionPropagates() {
    when(this.authService.authenticateUserDockerCli(AUTH_HEADER))
        .thenThrow(new IllegalStateException("database is down"));

    assertThatThrownBy(
            () ->
                this.handler.handle(
                    new ProtocolContext(), this.tokenRequest(), new MockHttpServletResponse()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("database is down");
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

  private static final String PULL_SCOPE = "repository:images/app:pull";

  private MockHttpServletRequest anonymousTokenRequest() {
    final var request = new MockHttpServletRequest("GET", "/v2/token");
    request.setParameter("scope", PULL_SCOPE);

    return request;
  }

  private static BaseRepoInfo<UUID> repo(final boolean privateRepo) {
    return BaseRepoInfo.<UUID>builder()
        .name("images")
        .storageKey(UUID.randomUUID())
        .privateRepo(privateRepo)
        .build();
  }

  @Test
  @DisplayName("hands an anonymous token to a caller without credentials for a public repo")
  void anonymousTokenForPublicRepo() throws Exception {
    final var repo = repo(false);
    doReturn(Optional.of(repo)).when(this.scopeParser).getRepoInfoByScope(PULL_SCOPE);
    when(this.authService.createAnonymousUser()).thenReturn("anon-tok");

    final var result =
        this.handler.handle(
            new ProtocolContext(), this.anonymousTokenRequest(), new MockHttpServletResponse());

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(((LoginResponse) result.getBody()).getToken()).isEqualTo("anon-tok");
    verify(this.authService).authorizePublicRead(repo);
  }

  @Test
  @DisplayName("answers 401 with a challenge, and no token, when the repo is not public")
  void noAnonymousTokenForPrivateRepo() throws Exception {
    final var repo = repo(true);
    doReturn(Optional.of(repo)).when(this.scopeParser).getRepoInfoByScope(PULL_SCOPE);
    doThrow(new ItemNotFoundException("repoNotFound"))
        .when(this.authService)
        .authorizePublicRead(repo);

    final var result =
        this.handler.handle(
            new ProtocolContext(), this.anonymousTokenRequest(), new MockHttpServletResponse());

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(result.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
        .startsWith("Basic realm=");
    assertThat(result.getBody()).isNull();
    verify(this.authService, never()).createAnonymousUser();
  }

  // -----------------------------------------------------------------------------------------
  // RPS-1220: an OAuth2-form-body password grant (helm registry login / oras-go) must actually
  // validate the credentials, not fall through to the anonymous-token path.
  // -----------------------------------------------------------------------------------------

  private static final String PASSWORD_GRANT_BASIC_HEADER =
      "Basic " + Base64.getEncoder().encodeToString("bob:secret".getBytes(StandardCharsets.UTF_8));

  private MockHttpServletRequest passwordGrantRequest(
      final String username, final String password) {
    final var request = new MockHttpServletRequest("POST", "/v2/token");
    request.setParameter("grant_type", "password");
    if (username != null) {
      request.setParameter("username", username);
    }
    if (password != null) {
      request.setParameter("password", password);
    }
    request.setParameter("scope", PULL_SCOPE);
    return request;
  }

  @Test
  @DisplayName(
      "grant_type=password with valid credentials authenticates through the Basic path and "
          + "returns the caller's own token, not an anonymous one")
  void passwordGrantWithValidCredentialsAuthenticates() throws Exception {
    when(this.authService.authenticateUserDockerCli(PASSWORD_GRANT_BASIC_HEADER))
        .thenReturn("user-tok-123");

    final var result =
        this.handler.handle(
            new ProtocolContext(),
            this.passwordGrantRequest("bob", "secret"),
            new MockHttpServletResponse());

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(((LoginResponse) result.getBody()).getToken()).isEqualTo("user-tok-123");
    verify(this.authService).authenticateUserDockerCli(PASSWORD_GRANT_BASIC_HEADER);
    verify(this.authService, never()).createAnonymousUser();
  }

  @Test
  @DisplayName(
      "grant_type=password with a wrong password answers 401 and never hands out an anonymous "
          + "token")
  void passwordGrantWithWrongPasswordIsRefused() throws Exception {
    when(this.authService.authenticateUserDockerCli(PASSWORD_GRANT_BASIC_HEADER))
        .thenThrow(new UnAuthorizedException("unAuthorized"));

    final var result =
        this.handler.handle(
            new ProtocolContext(),
            this.passwordGrantRequest("bob", "secret"),
            new MockHttpServletResponse());

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(result.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
        .startsWith("Basic realm=");
    verify(this.authService, never()).createAnonymousUser();
  }

  @Test
  @DisplayName("grant_type=password with a blank username falls through to the anonymous path")
  void passwordGrantWithBlankUsernameFallsThroughToAnonymous() throws Exception {
    final var repo = repo(false);
    doReturn(Optional.of(repo)).when(this.scopeParser).getRepoInfoByScope(PULL_SCOPE);
    when(this.authService.createAnonymousUser()).thenReturn("anon-tok");

    final var result =
        this.handler.handle(
            new ProtocolContext(),
            this.passwordGrantRequest("", "secret"),
            new MockHttpServletResponse());

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(((LoginResponse) result.getBody()).getToken()).isEqualTo("anon-tok");
    verify(this.authService, never()).authenticateUserDockerCli(any());
  }

  @Test
  @DisplayName("no grant_type at all is unchanged: still the anonymous 200 for a public scope")
  void noGrantTypeStaysAnonymous() throws Exception {
    final var repo = repo(false);
    doReturn(Optional.of(repo)).when(this.scopeParser).getRepoInfoByScope(PULL_SCOPE);
    when(this.authService.createAnonymousUser()).thenReturn("anon-tok");

    final var result =
        this.handler.handle(
            new ProtocolContext(), this.anonymousTokenRequest(), new MockHttpServletResponse());

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(((LoginResponse) result.getBody()).getToken()).isEqualTo("anon-tok");
  }

  @Test
  @DisplayName("an Authorization header wins over a form body present at the same time")
  void authorizationHeaderWinsOverFormBody() throws Exception {
    final var request = this.passwordGrantRequest("bob", "secret");
    request.addHeader(HttpHeaders.AUTHORIZATION, AUTH_HEADER);
    when(this.authService.authenticateUserDockerCli(AUTH_HEADER)).thenReturn("header-tok");

    final var result =
        this.handler.handle(new ProtocolContext(), request, new MockHttpServletResponse());

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(((LoginResponse) result.getBody()).getToken()).isEqualTo("header-tok");
    verify(this.authService).authenticateUserDockerCli(AUTH_HEADER);
    verify(this.authService, never()).authenticateUserDockerCli(PASSWORD_GRANT_BASIC_HEADER);
  }

  @Test
  @DisplayName("a password grant that is rate-limited propagates as 429, not 401")
  void passwordGrantTooManyRequestsPropagates() {
    when(this.authService.authenticateUserDockerCli(PASSWORD_GRANT_BASIC_HEADER))
        .thenThrow(new TooManyRequestsException(7));

    assertThatThrownBy(
            () ->
                this.handler.handle(
                    new ProtocolContext(),
                    this.passwordGrantRequest("bob", "secret"),
                    new MockHttpServletResponse()))
        .isInstanceOfSatisfying(
            TooManyRequestsException.class,
            ex -> assertThat(ex.getRetryAfterSeconds()).isEqualTo(7));
  }
}
