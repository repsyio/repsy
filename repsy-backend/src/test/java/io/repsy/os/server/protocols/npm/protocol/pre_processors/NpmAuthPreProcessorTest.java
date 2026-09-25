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
package io.repsy.os.server.protocols.npm.protocol.pre_processors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.server.core.UrlParserProperties;
import io.repsy.os.server.protocols.npm.shared.auth.services.NpmAuthComponentImpl;
import io.repsy.os.server.shared.auth.AuthFailureThrottle;
import io.repsy.os.server.shared.auth.AuthThrottleProperties;
import io.repsy.os.server.shared.auth.BasicAuthCacheProperties;
import io.repsy.os.server.shared.auth.VerifiedPasswordCache;
import io.repsy.os.server.shared.token.services.DeployTokenService;
import io.repsy.os.shared.auth.services.RevokedProtocolTokenService;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.TokenRealm;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * RPS-1209: the npm client reads only the first {@code WWW-Authenticate} value and takes the first
 * scheme it knows, so a refused Bearer credential is challenged with {@code Bearer} first (the
 * client then prints its "authentication token seems to be invalid" text) and every other refusal
 * keeps the Basic challenge.
 */
@DisplayName("NpmAuthPreProcessor")
class NpmAuthPreProcessorTest {

  private static final String BASIC_CHALLENGE = "Basic realm=\"Repsy Managed Registry\"";
  private static final String BEARER_CHALLENGE =
      "Bearer realm=\"Repsy Managed Registry\", " + BASIC_CHALLENGE;

  private final JwtUtils jwtUtils = Mockito.mock(JwtUtils.class);

  private final NpmAuthPreProcessor preProcessor =
      new NpmAuthPreProcessor(
          new NpmAuthComponentImpl(
              Mockito.mock(UserTxService.class),
              this.jwtUtils,
              Mockito.mock(DeployTokenService.class),
              new VerifiedPasswordCache(BasicAuthCacheProperties.disabled()),
              new AuthFailureThrottle(AuthThrottleProperties.disabled()),
              Mockito.mock(RevokedProtocolTokenService.class)),
          Mockito.mock(NpmProtocolProvider.class));

  NpmAuthPreProcessorTest() {
    when(this.jwtUtils.extractAuthenticationType(anyString(), any(TokenRealm.class)))
        .thenThrow(new UnAuthorizedException(ErrorConstants.ACCESS_NOT_ALLOWED));
  }

  private static ProtocolContext privateRepoContext() {
    final var repoInfo =
        RepoInfo.builder()
            .id(UUID.randomUUID())
            .storageKey(UUID.randomUUID())
            .name("registry")
            .privateRepo(true)
            .build();

    final var context = new ProtocolContext();
    context.addProperty(
        "urlProperties",
        UrlParserProperties.builder()
            .repoName("registry")
            .relativePath(new RelativePath(""))
            .repoInfo(repoInfo)
            .build());
    return context;
  }

  /** The {@code WWW-Authenticate} value of the 401 a read of a private repo is refused with. */
  private String challengeOf(final String authorization) {
    final var request = new MockHttpServletRequest();
    if (authorization != null) {
      request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
    }

    final var refused =
        catchThrowableOfType(
            UnAuthorizedException.class,
            () ->
                this.preProcessor.process(
                    privateRepoContext(),
                    request,
                    new MockHttpServletResponse(),
                    Map.of("permission", Permission.READ, "writeOperation", false)));

    assertThat(refused).as("the request is refused").isNotNull();
    return refused.getHeaders().get(HttpHeaders.WWW_AUTHENTICATE);
  }

  @Test
  @DisplayName("a refused Bearer credential is challenged with Bearer first, then Basic")
  void refusedBearer() {
    assertThat(this.challengeOf("Bearer not.a.token")).isEqualTo(BEARER_CHALLENGE);
  }

  @Test
  @DisplayName("a request without credentials keeps the Basic challenge")
  void noCredentials() {
    assertThat(this.challengeOf(null)).isEqualTo(BASIC_CHALLENGE);
  }

  @Test
  @DisplayName("a wrong Basic password keeps the Basic challenge")
  void wrongBasicPassword() {
    assertThat(this.challengeOf("Basic YWxpY2U6d3Jvbmc=")).isEqualTo(BASIC_CHALLENGE);
  }

  @Test
  @DisplayName("an unsupported scheme keeps the Basic challenge")
  void unsupportedScheme() {
    assertThat(this.challengeOf("Digest username=\"x\"")).isEqualTo(BASIC_CHALLENGE);
  }

  private static ProtocolContext publicRepoContext() {
    final var repoInfo =
        RepoInfo.builder()
            .id(UUID.randomUUID())
            .storageKey(UUID.randomUUID())
            .name("registry")
            .privateRepo(false)
            .build();

    final var context = new ProtocolContext();
    context.addProperty(
        "urlProperties",
        UrlParserProperties.builder()
            .repoName("registry")
            .relativePath(new RelativePath(""))
            .repoInfo(repoInfo)
            .build());
    return context;
  }

  @Test
  @DisplayName("a read of a public repo needs no credentials")
  void publicReadIsOpen() {
    final var result =
        this.preProcessor.process(
            publicRepoContext(),
            new MockHttpServletRequest(),
            new MockHttpServletResponse(),
            Map.of("permission", Permission.READ, "writeOperation", false));

    assertThat(result).isNotNull();
  }

  @Test
  @DisplayName("requireAuthentication asks for credentials on a public repo too")
  void requireAuthenticationOnAPublicRepo() {
    final var refused =
        catchThrowableOfType(
            UnAuthorizedException.class,
            () ->
                this.preProcessor.process(
                    publicRepoContext(),
                    new MockHttpServletRequest(),
                    new MockHttpServletResponse(),
                    Map.of(
                        "permission",
                        Permission.READ,
                        "writeOperation",
                        false,
                        "requireAuthentication",
                        true)));

    assertThat(refused).isNotNull();
    assertThat(refused.getHeaders().get(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo(BASIC_CHALLENGE);
  }

  @Test
  @DisplayName("skipPreProcessor still wins over requireAuthentication")
  void skipWinsOverRequire() {
    final var result =
        this.preProcessor.process(
            privateRepoContext(),
            new MockHttpServletRequest(),
            new MockHttpServletResponse(),
            Map.of(
                "permission",
                Permission.NONE,
                "writeOperation",
                false,
                "skipPreProcessor",
                true,
                "requireAuthentication",
                true));

    assertThat(result).isNotNull();
  }
}
