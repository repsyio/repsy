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
package io.repsy.os.server.protocols.cargo.protocol.pre_processors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.ProcessorResult;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.server.core.UrlParserProperties;
import io.repsy.os.server.protocols.cargo.shared.auth.services.CargoAuthComponent;
import io.repsy.os.server.shared.auth.AuthFailureThrottle;
import io.repsy.os.server.shared.auth.AuthThrottleProperties;
import io.repsy.os.server.shared.auth.BasicAuthCacheProperties;
import io.repsy.os.server.shared.auth.VerifiedPasswordCache;
import io.repsy.os.server.shared.token.services.DeployTokenService;
import io.repsy.os.shared.auth.dtos.AuthenticationType;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.TokenRealm;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.user.mappers.UserConverter;
import io.repsy.os.shared.user.repositories.UserRepository;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
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
 * RPS-1027: what the Cargo protocol does with a valid token of a user who no longer exists. It is
 * tested through the pre-processor, the path a request takes, because {@code
 * CargoAuthComponent.resolveBearerAuthUser} that used to hold this decision had no caller
 * (RPS-979).
 */
@DisplayName("CargoAuthPreProcessor")
class CargoAuthPreProcessorTest {

  /** The Cargo CLI sends the token as a raw value with no scheme. */
  private static final String RAW_TOKEN = "signed.jwt.token";

  private final JwtUtils jwtUtils = Mockito.mock(JwtUtils.class);

  private final CargoAuthPreProcessor preProcessor =
      new CargoAuthPreProcessor(
          new CargoAuthComponent(
              // A real UserTxService over an empty repository: the user of the token is gone.
              new UserTxService(
                  Mockito.mock(UserRepository.class), Mockito.mock(UserConverter.class)),
              this.jwtUtils,
              Mockito.mock(DeployTokenService.class),
              new VerifiedPasswordCache(BasicAuthCacheProperties.disabled()),
              new AuthFailureThrottle(AuthThrottleProperties.disabled())),
          Mockito.mock(CargoProtocolProvider.class));

  CargoAuthPreProcessorTest() {
    when(this.jwtUtils.extractAuthenticationType(anyString(), any(TokenRealm.class)))
        .thenReturn(AuthenticationType.USERNAME_PASSWORD);
    when(this.jwtUtils.verifyAndExtractUsername(anyString(), any(TokenRealm.class)))
        .thenReturn("ghost");
  }

  private static ProtocolContext contextOf(final boolean privateRepo) {
    final var repoInfo =
        RepoInfo.builder()
            .id(UUID.randomUUID())
            .storageKey(UUID.randomUUID())
            .name("crates")
            .privateRepo(privateRepo)
            .build();

    final var context = new ProtocolContext();
    context.addProperty(
        "urlProperties",
        UrlParserProperties.builder()
            .repoName("crates")
            .relativePath(new RelativePath(""))
            .repoInfo(repoInfo)
            .build());
    return context;
  }

  private static Map<String, Object> properties(
      final Permission permission, final boolean writeOperation) {
    return Map.of("permission", permission, "writeOperation", writeOperation);
  }

  private ProcessorResult process(
      final boolean privateRepo, final Permission permission, final boolean writeOperation) {
    final var request = new MockHttpServletRequest();
    request.addHeader(HttpHeaders.AUTHORIZATION, RAW_TOKEN);

    return this.preProcessor.process(
        contextOf(privateRepo),
        request,
        new MockHttpServletResponse(),
        properties(permission, writeOperation));
  }

  @Test
  @DisplayName("a write to a public repo answers unAuthorized")
  void writeToPublicRepo() {
    assertThatThrownBy(() -> this.process(false, Permission.WRITE, true))
        .isExactlyInstanceOf(UnAuthorizedException.class)
        .hasMessage(ErrorConstants.UN_AUTHORIZED);
  }

  @Test
  @DisplayName("a read of a private repo answers unAuthorized")
  void readOfPrivateRepo() {
    assertThatThrownBy(() -> this.process(true, Permission.READ, false))
        .isExactlyInstanceOf(UnAuthorizedException.class)
        .hasMessage(ErrorConstants.UN_AUTHORIZED);
  }

  /**
   * A public read never reaches authentication, in any protocol: the pre-processor lets it through
   * without looking at the token, so a token of a deleted user makes no difference to it.
   */
  @Test
  @DisplayName("a read of a public repo is served without the token being looked at")
  void readOfPublicRepo() {
    final var result = this.process(false, Permission.READ, false);

    assertThat(result.isEmpty()).isTrue();
    verify(this.jwtUtils, never()).verifyAndExtractUsername(anyString(), any(TokenRealm.class));
  }
}
