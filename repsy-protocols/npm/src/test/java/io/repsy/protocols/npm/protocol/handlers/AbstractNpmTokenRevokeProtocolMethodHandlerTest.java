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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.npm.protocol.handlers.NpmHandlerTestSupport.FixedBaseParser;
import io.repsy.protocols.npm.shared.auth.services.NpmTokenRevoker;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * RPS-1361: {@code npm logout} and {@code pnpm logout} send {@code DELETE /-/user/token/<token>}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNpmTokenRevokeProtocolMethodHandler")
class AbstractNpmTokenRevokeProtocolMethodHandlerTest {

  private static final String PATH = "/-/user/token/abc.def.ghi";

  @Mock private NpmTokenRevoker<UUID> revoker;
  @Mock private NpmProtocolProvider provider;

  private static class TestHandler extends AbstractNpmTokenRevokeProtocolMethodHandler<UUID> {
    TestHandler(
        final PathParser base,
        final NpmTokenRevoker<UUID> revoker,
        final NpmProtocolProvider provider) {
      super(base, revoker, provider);
    }
  }

  private TestHandler handler() {
    return new TestHandler(new FixedBaseParser(PATH, true), this.revoker, this.provider);
  }

  @Test
  @DisplayName("registers for DELETE, reads, and asks the pre-processor to authenticate")
  void metadata() {
    final var handler = this.handler();

    verify(this.provider).registerMethodHandler(handler);
    assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.DELETE);
    assertThat(handler.getProperties())
        .containsEntry("permission", Permission.READ)
        .containsEntry("writeOperation", false)
        .containsEntry("requireAuthentication", true)
        .containsEntry("skipUsagePostProcessor", true);
  }

  @Test
  @DisplayName("claims DELETE /-/user/token/{token} and nothing else")
  void parser() {
    final var parser = this.handler().getPathParser();

    assertThat(parser.parse(NpmHandlerTestSupport.request("DELETE", "/npm/-/user/token/abc")))
        .isPresent();
    assertThat(parser.parse(NpmHandlerTestSupport.request("GET", "/npm/-/user/token/abc")))
        .isEmpty();
    assertThat(parser.parse(NpmHandlerTestSupport.request("PUT", "/npm/-/user/token/abc")))
        .isEmpty();
    assertThat(parser.parse(NpmHandlerTestSupport.request("DELETE", "/npm/-/user/token/")))
        .isEmpty();
    assertThat(parser.parse(NpmHandlerTestSupport.request("DELETE", "/npm/-/user/token/a/b")))
        .isEmpty();
    assertThat(parser.parse(NpmHandlerTestSupport.request("DELETE", "/npm/-/user/token")))
        .isEmpty();
    assertThat(parser.parse(NpmHandlerTestSupport.request("DELETE", "/npm/left-pad"))).isEmpty();
  }

  @Test
  @DisplayName("revokes the token of the path with the credentials of the request and answers ok")
  void revokes() {
    final var request = NpmHandlerTestSupport.request("DELETE", "/npm" + PATH);
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer abc.def.ghi");

    final var response =
        this.handler()
            .handle(NpmHandlerTestSupport.context(PATH), request, new MockHttpServletResponse());

    verify(this.revoker).revokeToken(any(), eq("Bearer abc.def.ghi"), eq("abc.def.ghi"));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(response.getBody()).isEqualTo(Map.of("ok", true));
  }

  @Test
  @DisplayName("answers 401 with the Basic and Bearer challenge when the credentials are refused")
  void refusedBearer() {
    final var request = NpmHandlerTestSupport.request("DELETE", "/npm" + PATH);
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer nope");
    doThrow(new UnAuthorizedException("unAuthorized"))
        .when(this.revoker)
        .revokeToken(any(), any(), any());

    final var response =
        this.handler()
            .handle(NpmHandlerTestSupport.context(PATH), request, new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
        .startsWith("Bearer realm=")
        .contains("Basic realm=");
    assertThat(response.getBody()).isEqualTo(Map.of("error", "unAuthorized"));
  }

  @Test
  @DisplayName("answers 401 with the Basic challenge for a request without a Bearer credential")
  void refusedWithoutCredentials() {
    doThrow(new UnAuthorizedException("unAuthorized"))
        .when(this.revoker)
        .revokeToken(any(), any(), any());

    final var response =
        this.handler()
            .handle(
                NpmHandlerTestSupport.context(PATH),
                NpmHandlerTestSupport.request("DELETE", "/npm" + PATH),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
        .startsWith("Basic realm=");
  }

  @Test
  @DisplayName("answers 403 in the npm error shape, with the msgId, for a refusal (RPS-1391)")
  void forbiddenBody() {
    doThrow(new AccessNotAllowedException("deployTokenNotRevocable"))
        .when(this.revoker)
        .revokeToken(any(), any(), any());
    final var request = NpmHandlerTestSupport.request("DELETE", "/npm" + PATH);

    final var response =
        this.handler()
            .handle(NpmHandlerTestSupport.context(PATH), request, new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNull();
    assertThat(response.getBody())
        .isEqualTo(Map.of("error", "deployTokenNotRevocable", "msgId", "deployTokenNotRevocable"));
  }

  @Test
  @DisplayName("uses the text the application resolves for the msgId, and keeps the msgId")
  void forbiddenBodyWithApplicationText() {
    doThrow(new AccessNotAllowedException("loginTokenNotYours"))
        .when(this.revoker)
        .revokeToken(any(), any(), any());
    final var request = NpmHandlerTestSupport.request("DELETE", "/npm" + PATH);
    final var handler =
        new TestHandler(new FixedBaseParser(PATH, true), this.revoker, this.provider) {
          @Override
          protected String forbiddenText(final String msgId) {
            return "text of " + msgId;
          }
        };

    final var response =
        handler.handle(NpmHandlerTestSupport.context(PATH), request, new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody())
        .isEqualTo(Map.of("error", "text of loginTokenNotYours", "msgId", "loginTokenNotYours"));
  }

  @Test
  @DisplayName("falls back to accessNotAllowed for a refusal without a message id")
  void forbiddenWithoutMsgId() {
    doThrow(new AccessNotAllowedException(null))
        .when(this.revoker)
        .revokeToken(any(), any(), any());
    final var request = NpmHandlerTestSupport.request("DELETE", "/npm" + PATH);

    final var response =
        this.handler()
            .handle(NpmHandlerTestSupport.context(PATH), request, new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody())
        .isEqualTo(Map.of("error", "accessNotAllowed", "msgId", "accessNotAllowed"));
  }
}
