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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.npm.protocol.handlers.NpmHandlerTestSupport.FixedBaseParser;
import io.repsy.protocols.npm.shared.auth.services.NpmIdentityResolver;
import io.repsy.protocols.shared.exceptions.TooManyRequestsException;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNpmWhoamiProtocolMethodHandler")
class AbstractNpmWhoamiProtocolMethodHandlerTest {

  @Mock private NpmIdentityResolver<UUID> resolver;
  @Mock private NpmProtocolProvider provider;

  private static class TestHandler extends AbstractNpmWhoamiProtocolMethodHandler<UUID> {
    TestHandler(
        final PathParser base,
        final NpmIdentityResolver<UUID> resolver,
        final NpmProtocolProvider provider) {
      super(base, resolver, provider);
    }
  }

  private TestHandler handler() {
    return new TestHandler(new FixedBaseParser("/-/whoami", true), this.resolver, this.provider);
  }

  @Test
  @DisplayName("registers for GET, reads, and asks the pre-processor to authenticate")
  void metadata() {
    final var handler = this.handler();

    verify(this.provider).registerMethodHandler(handler);
    assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.GET);
    assertThat(handler.getProperties())
        .containsEntry("permission", Permission.READ)
        .containsEntry("writeOperation", false)
        .containsEntry("requireAuthentication", true)
        .containsEntry("skipUsagePostProcessor", true);
  }

  @Test
  @DisplayName("claims GET /-/whoami and nothing else")
  void parser() {
    final var parser = this.handler().getPathParser();

    assertThat(parser.parse(NpmHandlerTestSupport.request("GET", "/npm/-/whoami"))).isPresent();
    assertThat(parser.parse(NpmHandlerTestSupport.request("GET", "/npm/-/ping"))).isEmpty();
    assertThat(parser.parse(NpmHandlerTestSupport.request("PUT", "/npm/-/whoami"))).isEmpty();
  }

  @Test
  @DisplayName("answers the username of the credentials")
  void answersTheUsername() {
    final var context = NpmHandlerTestSupport.context("/-/whoami");
    final var request = NpmHandlerTestSupport.request("GET", "/npm/-/whoami");
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer abc");
    when(this.resolver.resolveUsername(any(), eq("Bearer abc"))).thenReturn("alice");

    final var response = this.handler().handle(context, request, new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(response.getBody()).isEqualTo(Map.of("username", "alice"));
  }

  @Test
  @DisplayName("answers 401 with the Basic challenge when there are no credentials")
  void unauthorizedWithoutCredentials() {
    when(this.resolver.resolveUsername(any(), eq(null)))
        .thenThrow(new UnAuthorizedException("unAuthorized"));

    final var response =
        this.handler()
            .handle(
                NpmHandlerTestSupport.context("/-/whoami"),
                NpmHandlerTestSupport.request("GET", "/npm/-/whoami"),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
        .isEqualTo("Basic realm=\"Repsy Managed Registry\"");
    assertThat(response.getBody()).isEqualTo(Map.of("error", "unAuthorized"));
  }

  @Test
  @DisplayName("challenges a refused Bearer credential with Bearer first, then Basic")
  void unauthorizedBearer() {
    final var request = NpmHandlerTestSupport.request("GET", "/npm/-/whoami");
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer junk");
    when(this.resolver.resolveUsername(any(), eq("Bearer junk")))
        .thenThrow(new UnAuthorizedException("unAuthorized"));

    final var response =
        this.handler()
            .handle(
                NpmHandlerTestSupport.context("/-/whoami"), request, new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
        .isEqualTo(
            "Bearer realm=\"Repsy Managed Registry\", Basic realm=\"Repsy Managed Registry\"");
  }

  @Test
  @DisplayName("lets a throttled client see 429 instead of asking it to log in again")
  void tooManyRequestsPropagates() {
    when(this.resolver.resolveUsername(any(), eq(null)))
        .thenThrow(new TooManyRequestsException(30));

    assertThatThrownBy(
            () ->
                this.handler()
                    .handle(
                        NpmHandlerTestSupport.context("/-/whoami"),
                        NpmHandlerTestSupport.request("GET", "/npm/-/whoami"),
                        new MockHttpServletResponse()))
        .isInstanceOf(TooManyRequestsException.class);
  }
}
