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
import static org.mockito.Mockito.verifyNoInteractions;
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
    when(this.authService.authenticateUserDockerCli(AUTH_HEADER, List.of())).thenReturn("tok-123");

    final var result =
        this.handler.handle(
            new ProtocolContext(), this.tokenRequest(), new MockHttpServletResponse());

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(result.getBody()).isInstanceOf(LoginResponse.class);
    assertThat(((LoginResponse) result.getBody()).getToken()).isEqualTo("tok-123");
  }

  /**
   * RPS-1434: the token records what the client asked for. Each {@code scope} parameter may hold
   * several space separated scopes, and a cross-repo blob mount sends more than one parameter.
   */
  @Test
  @DisplayName("passes every requested repository scope to the token it issues")
  void passesTheRequestedScopes() throws Exception {
    final var request = this.tokenRequest();
    request.addParameter("scope", "repository:images/app:pull,push repository:images/lib:pull");
    request.addParameter("scope", "repository:other/x:delete");
    request.addParameter("scope", "registry:catalog:*");
    when(this.authService.authenticateUserDockerCli(
            AUTH_HEADER, List.of("images/app:pull,push", "images/lib:pull", "other/x:delete")))
        .thenReturn("tok-scoped");

    final var result =
        this.handler.handle(new ProtocolContext(), request, new MockHttpServletResponse());

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(((LoginResponse) result.getBody()).getToken()).isEqualTo("tok-scoped");
  }

  @Test
  @DisplayName("answers 401 with a challenge when the credentials are wrong")
  void wrongCredentials() throws Exception {
    when(this.authService.authenticateUserDockerCli(AUTH_HEADER, List.of()))
        .thenThrow(new UnAuthorizedException("unAuthorized"));

    assertThatThrownBy(
            () ->
                this.handler.handle(
                    new ProtocolContext(), this.tokenRequest(), new MockHttpServletResponse()))
        .satisfies(e -> expectChallenge(e, "unAuthorized"));
  }

  /**
   * RPS-1435: the 401 is thrown so the error handler writes the OCI error body, whose message the
   * client prints, and it keeps the token endpoint's Basic challenge.
   */
  private static void expectChallenge(final Throwable thrown, final String msgId) {
    assertThat(thrown).isInstanceOf(UnAuthorizedException.class).hasMessage(msgId);
    assertThat(((UnAuthorizedException) thrown).getHeaders())
        .hasEntrySatisfying(
            HttpHeaders.WWW_AUTHENTICATE,
            challenge -> assertThat(challenge).startsWith("Basic realm="));
  }

  @Test
  @DisplayName("keeps the cause the credential check named, an expired deploy token (RPS-1435)")
  void expiredDeployTokenKeepsItsCause() throws Exception {
    when(this.authService.authenticateUserDockerCli(AUTH_HEADER, List.of()))
        .thenThrow(new UnAuthorizedException("deployTokenExpired"));

    assertThatThrownBy(
            () ->
                this.handler.handle(
                    new ProtocolContext(), this.tokenRequest(), new MockHttpServletResponse()))
        .satisfies(e -> expectChallenge(e, "deployTokenExpired"));
  }

  /**
   * RPS-1165: only a credential failure is answered as 401. Anything else (a database outage, a
   * bug) must reach {@code ErrorHandler} so it is logged and answered 500, instead of looking like
   * a wrong credential to the client.
   */
  @Test
  @DisplayName("lets a non-authentication exception propagate instead of answering 401")
  void nonAuthenticationExceptionPropagates() {
    when(this.authService.authenticateUserDockerCli(AUTH_HEADER, List.of()))
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
    when(this.authService.authenticateUserDockerCli(AUTH_HEADER, List.of()))
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
  private static final List<String> PULL_GRANTS = List.of("images/app:pull");

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

    assertThatThrownBy(
            () ->
                this.handler.handle(
                    new ProtocolContext(),
                    this.anonymousTokenRequest(),
                    new MockHttpServletResponse()))
        .satisfies(e -> expectChallenge(e, "unAuthorized"));
    verify(this.authService, never()).createAnonymousUser();
  }

  private MockHttpServletRequest anonymousRequestForScope(final String scope) {
    final var request = new MockHttpServletRequest("GET", "/v2/token");
    request.setParameter("scope", scope);

    return request;
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "repository:repo/app:delete",
        "repository:repo/app:pull,delete",
        "repository:repo/app:pull repository:repo/other:delete",
        "repository:repo/app:DELETE"
      })
  @DisplayName("a delete scope is never anonymous, even for a public repo (RPS-1216)")
  void deleteScopeNeedsCredentials(final String scope) throws Exception {
    assertThatThrownBy(
            () ->
                this.handler.handle(
                    new ProtocolContext(),
                    this.anonymousRequestForScope(scope),
                    new MockHttpServletResponse()))
        .satisfies(e -> expectChallenge(e, "unauthorizedRequest"));
    verify(this.authService, never()).createAnonymousUser();
    verifyNoInteractions(this.scopeParser);
  }

  @ParameterizedTest
  @ValueSource(strings = {"repository:repo/delete-me:pull", "repository:repo/undelete:pull"})
  @DisplayName("an image merely named like the action is still a public pull")
  void imageNamedDeleteIsStillAPublicPull(final String scope) throws Exception {
    doReturn(Optional.of(repo(false))).when(this.scopeParser).getRepoInfoByScope(scope);
    when(this.authService.createAnonymousUser()).thenReturn("anon-tok");

    final var result =
        this.handler.handle(
            new ProtocolContext(),
            this.anonymousRequestForScope(scope),
            new MockHttpServletResponse());

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
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
    when(this.authService.authenticateUserDockerCli(PASSWORD_GRANT_BASIC_HEADER, PULL_GRANTS))
        .thenReturn("user-tok-123");

    final var result =
        this.handler.handle(
            new ProtocolContext(),
            this.passwordGrantRequest("bob", "secret"),
            new MockHttpServletResponse());

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(((LoginResponse) result.getBody()).getToken()).isEqualTo("user-tok-123");
    verify(this.authService).authenticateUserDockerCli(PASSWORD_GRANT_BASIC_HEADER, PULL_GRANTS);
    verify(this.authService, never()).createAnonymousUser();
  }

  @Test
  @DisplayName(
      "grant_type=password with a wrong password answers 401 and never hands out an anonymous "
          + "token")
  void passwordGrantWithWrongPasswordIsRefused() throws Exception {
    when(this.authService.authenticateUserDockerCli(PASSWORD_GRANT_BASIC_HEADER, PULL_GRANTS))
        .thenThrow(new UnAuthorizedException("unAuthorized"));

    assertThatThrownBy(
            () ->
                this.handler.handle(
                    new ProtocolContext(),
                    this.passwordGrantRequest("bob", "secret"),
                    new MockHttpServletResponse()))
        .satisfies(e -> expectChallenge(e, "unAuthorized"));
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
    verify(this.authService, never()).authenticateUserDockerCli(any(), any());
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
    when(this.authService.authenticateUserDockerCli(AUTH_HEADER, PULL_GRANTS))
        .thenReturn("header-tok");

    final var result =
        this.handler.handle(new ProtocolContext(), request, new MockHttpServletResponse());

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(((LoginResponse) result.getBody()).getToken()).isEqualTo("header-tok");
    verify(this.authService).authenticateUserDockerCli(AUTH_HEADER, PULL_GRANTS);
    verify(this.authService, never())
        .authenticateUserDockerCli(PASSWORD_GRANT_BASIC_HEADER, PULL_GRANTS);
  }

  @Test
  @DisplayName("a password grant that is rate-limited propagates as 429, not 401")
  void passwordGrantTooManyRequestsPropagates() {
    when(this.authService.authenticateUserDockerCli(PASSWORD_GRANT_BASIC_HEADER, PULL_GRANTS))
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

  private MockHttpServletRequest anonymousRequestForScopes(final String... scopes) {
    final var request = new MockHttpServletRequest("GET", "/v2/token");

    for (final var scope : scopes) {
      request.addParameter("scope", scope);
    }

    return request;
  }

  /**
   * RPS-1588: containerd and crane send one {@code scope} parameter per scope, the placeholder
   * {@code repository:*:pull} first because {@code *} sorts before every letter. Every value is
   * judged, so a push or delete scope after the placeholder is not answered with an anonymous
   * token.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "repository:images/app:pull,push",
        "repository:images/app:push,pull",
        "repository:images/app:delete",
        "repository:images/app:*",
        "registry:catalog:*"
      })
  @DisplayName("a scope after the placeholder that needs credentials is judged too (RPS-1588)")
  void everyScopeValueIsJudged(final String second) {
    assertThatThrownBy(
            () ->
                this.handler.handle(
                    new ProtocolContext(),
                    this.anonymousRequestForScopes("repository:*:pull", second),
                    new MockHttpServletResponse()))
        .satisfies(e -> expectChallenge(e, "unauthorizedRequest"));
    verify(this.authService, never()).createAnonymousUser();
    verifyNoInteractions(this.scopeParser);
  }

  @Test
  @DisplayName("the placeholder next to a public pull scope is still an anonymous token")
  void placeholderNextToPublicPull() throws Exception {
    final var repo = repo(false);
    doReturn(Optional.of(repo))
        .when(this.scopeParser)
        .getRepoInfoByScope("repository:*:pull " + PULL_SCOPE);
    when(this.authService.createAnonymousUser()).thenReturn("anon-tok");

    final var result =
        this.handler.handle(
            new ProtocolContext(),
            this.anonymousRequestForScopes("repository:*:pull", PULL_SCOPE),
            new MockHttpServletResponse());

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(this.authService).authorizePublicRead(repo);
  }

  @Test
  @DisplayName("the placeholder next to a private pull scope is not an anonymous token")
  void placeholderNextToPrivatePull() throws Exception {
    final var repo = repo(true);
    doReturn(Optional.of(repo))
        .when(this.scopeParser)
        .getRepoInfoByScope("repository:*:pull " + PULL_SCOPE);
    doThrow(new ItemNotFoundException("repoNotFound"))
        .when(this.authService)
        .authorizePublicRead(repo);

    assertThatThrownBy(
            () ->
                this.handler.handle(
                    new ProtocolContext(),
                    this.anonymousRequestForScopes("repository:*:pull", PULL_SCOPE),
                    new MockHttpServletResponse()))
        .satisfies(e -> expectChallenge(e, "unAuthorized"));
    verify(this.authService, never()).createAnonymousUser();
  }

  @Test
  @DisplayName("a token request without any scope value is challenged")
  void noScopeValue() {
    assertThatThrownBy(
            () ->
                this.handler.handle(
                    new ProtocolContext(),
                    this.anonymousRequestForScopes("  "),
                    new MockHttpServletResponse()))
        .satisfies(e -> expectChallenge(e, "unauthorizedRequest"));
  }
}
