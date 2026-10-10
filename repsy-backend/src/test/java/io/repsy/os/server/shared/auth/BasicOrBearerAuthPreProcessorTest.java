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
package io.repsy.os.server.shared.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.ProcessorResult;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolProvider;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.server.core.UrlParserProperties;
import io.repsy.os.server.shared.utils.UrlPropertiesUtils;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.shared.auth.BasicAuthChallenge;
import io.repsy.protocols.shared.handlers.HandlerPropertyKeys;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * RPS-2062: the steps every protocol's pre-processor takes by default. The skip rule is the one
 * {@code PreProcessorUtils} held for Cargo, Ruby, Helm and NuGet (RPS-1576), and the one Maven, Go,
 * PyPI and npm wrote out for themselves, plus two cases none of them decided safely: a route that
 * needs credentials on a public repo ({@code requireAuthentication}, npm's {@code whoami}) and a
 * route that does not say whether it writes.
 */
@DisplayName("BasicOrBearerAuthPreProcessor")
class BasicOrBearerAuthPreProcessorTest {

  private static final UUID STORAGE_KEY = UUID.randomUUID();

  private final ProtocolAuthService authenticator = mock(ProtocolAuthService.class);
  private final HttpServletRequest request = mock(HttpServletRequest.class);

  private final BasicOrBearerAuthPreProcessor<ProtocolAuthService> preProcessor =
      new BasicOrBearerAuthPreProcessor<>(this.authenticator, mock(ProtocolProvider.class)) {};

  private static RepoInfo repoOf(final boolean privateRepo) {
    return RepoInfo.builder()
        .id(STORAGE_KEY)
        .storageKey(STORAGE_KEY)
        .name("repo")
        .privateRepo(privateRepo)
        .build();
  }

  private static ProtocolContext contextOf(final RepoInfo repoInfo) {
    final var context = new ProtocolContext();
    context.addProperty(
        "urlProperties",
        UrlParserProperties.builder()
            .repoName("repo")
            .relativePath(new RelativePath("/file"))
            .repoInfo(repoInfo)
            .build());
    return context;
  }

  @Nested
  @DisplayName("shouldSkipAuthentication")
  class ShouldSkipAuthentication {

    private boolean shouldSkip(final boolean privateRepo, final Map<String, Object> properties) {
      return BasicOrBearerAuthPreProcessorTest.this.preProcessor.shouldSkipAuthentication(
          repoOf(privateRepo), properties);
    }

    @Test
    @DisplayName("skipPreProcessor always skips, even for a private write")
    void skipPropertyWins() {
      assertThat(
              this.shouldSkip(
                  true,
                  Map.of(
                      HandlerPropertyKeys.SKIP_PRE_PROCESSOR,
                      true,
                      HandlerPropertyKeys.WRITE_OPERATION,
                      true)))
          .isTrue();
    }

    @Test
    @DisplayName("a read of a public repo is skipped, a write to it is not")
    void publicRepo() {
      assertThat(this.shouldSkip(false, Map.of(HandlerPropertyKeys.WRITE_OPERATION, false)))
          .isTrue();
      assertThat(this.shouldSkip(false, Map.of(HandlerPropertyKeys.WRITE_OPERATION, true)))
          .isFalse();
    }

    @Test
    @DisplayName("nothing on a private repo is skipped")
    void privateRepo() {
      assertThat(this.shouldSkip(true, Map.of(HandlerPropertyKeys.WRITE_OPERATION, false)))
          .isFalse();
      assertThat(this.shouldSkip(true, Map.of(HandlerPropertyKeys.WRITE_OPERATION, true)))
          .isFalse();
    }

    @Test
    @DisplayName("requireAuthentication authenticates a read of a public repo")
    void requireAuthentication() {
      assertThat(
              this.shouldSkip(
                  false,
                  Map.of(
                      HandlerPropertyKeys.WRITE_OPERATION,
                      false,
                      HandlerPropertyKeys.REQUIRE_AUTHENTICATION,
                      true)))
          .isFalse();
    }

    @Test
    @DisplayName("without writeOperation, a route that needs WRITE or MANAGE counts as a write")
    void missingWriteOperationFollowsThePermission() {
      assertThat(this.shouldSkip(false, Map.of())).isTrue();
      assertThat(this.shouldSkip(false, Map.of(HandlerPropertyKeys.PERMISSION, Permission.READ)))
          .isTrue();
      assertThat(this.shouldSkip(false, Map.of(HandlerPropertyKeys.PERMISSION, Permission.WRITE)))
          .isFalse();
      assertThat(this.shouldSkip(false, Map.of(HandlerPropertyKeys.PERMISSION, Permission.MANAGE)))
          .isFalse();
    }

    @Test
    @DisplayName("an explicit writeOperation=false is taken as it is")
    void explicitWriteOperationWins() {
      assertThat(
              this.shouldSkip(
                  false,
                  Map.of(
                      HandlerPropertyKeys.PERMISSION,
                      Permission.WRITE,
                      HandlerPropertyKeys.WRITE_OPERATION,
                      false)))
          .isTrue();
    }
  }

  @Nested
  @DisplayName("process")
  class Process {

    private final Map<String, Object> properties = new HashMap<>();

    Process() {
      this.properties.put(HandlerPropertyKeys.PERMISSION, Permission.READ);
      this.properties.put(HandlerPropertyKeys.WRITE_OPERATION, false);
    }

    private ProcessorResult process(final String header) {
      when(BasicOrBearerAuthPreProcessorTest.this.authenticator.emulateAuthHeader(any()))
          .thenReturn(header);

      return BasicOrBearerAuthPreProcessorTest.this.preProcessor.process(
          contextOf(repoOf(true)),
          BasicOrBearerAuthPreProcessorTest.this.request,
          mock(HttpServletResponse.class),
          this.properties);
    }

    @Test
    @DisplayName("no credential: a 401 with the Basic challenge and no body")
    void missingCredential() {
      final var result = this.process(null);

      assertThat(result.isEmpty()).isFalse();
      final var response = result.getResult();
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
      assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
          .isEqualTo(BasicAuthChallenge.REPSY);
      assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("a Basic credential is checked against the repo's storage key")
    void basicCredential() {
      this.process("Basic dXNlcjpwYXNz");

      verify(BasicOrBearerAuthPreProcessorTest.this.authenticator)
          .handleBasicAuth("Basic dXNlcjpwYXNz", Permission.READ, STORAGE_KEY);
    }

    @Test
    @DisplayName("a Bearer credential is checked against the repo's storage key")
    void bearerCredential() {
      this.process("Bearer a.b.c");

      verify(BasicOrBearerAuthPreProcessorTest.this.authenticator)
          .handleBearerAuth("Bearer a.b.c", STORAGE_KEY, Permission.READ);
    }

    @Test
    @DisplayName("a token without a scheme is refused, not read as a Bearer token")
    void bareTokenIsRefusedByDefault() {
      assertThatThrownBy(() -> this.process("a.b.c"))
          .isInstanceOf(UnAuthorizedException.class)
          .satisfies(
              ex ->
                  assertThat(((UnAuthorizedException) ex).getHeaders())
                      .containsEntry(HttpHeaders.WWW_AUTHENTICATE, BasicAuthChallenge.REPSY));

      verify(BasicOrBearerAuthPreProcessorTest.this.authenticator, never())
          .handleBearerAuth(anyString(), any(), any());
    }

    @Test
    @DisplayName("a refused credential is thrown with the challenge attached")
    void refusedCredential() {
      doThrow(new UnAuthorizedException("unAuthorized"))
          .when(BasicOrBearerAuthPreProcessorTest.this.authenticator)
          .handleBearerAuth(anyString(), any(), any());

      assertThatThrownBy(() -> this.process("Bearer forged"))
          .isInstanceOf(UnAuthorizedException.class)
          .hasMessage("unAuthorized")
          .satisfies(
              ex ->
                  assertThat(((UnAuthorizedException) ex).getHeaders())
                      .containsEntry(HttpHeaders.WWW_AUTHENTICATE, BasicAuthChallenge.REPSY));
    }

    @Test
    @DisplayName("a skipped request reads no credential")
    void skippedRequest() {
      this.properties.put(HandlerPropertyKeys.SKIP_PRE_PROCESSOR, true);

      final var result = this.process("Bearer x");

      assertThat(result.isEmpty()).isTrue();
      verify(BasicOrBearerAuthPreProcessorTest.this.authenticator, never())
          .emulateAuthHeader(any());
    }
  }

  @Test
  @DisplayName("registers itself with the provider")
  void registersWithTheProvider() {
    final var provider = mock(ProtocolProvider.class);

    final var registered = new BasicOrBearerAuthPreProcessor<>(this.authenticator, provider) {};

    verify(provider).registerPreProcessor(registered);
  }

  @Test
  @DisplayName("UrlPropertiesUtils reads the repo the context carries")
  void contextCarriesTheRepo() {
    assertThat(UrlPropertiesUtils.getRepoInfo(contextOf(repoOf(true))).getStorageKey())
        .isEqualTo(STORAGE_KEY);
  }
}
