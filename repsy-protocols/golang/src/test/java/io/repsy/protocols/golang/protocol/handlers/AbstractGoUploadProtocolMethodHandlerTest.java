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
package io.repsy.protocols.golang.protocol.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.golang.protocol.GolangProtocolProvider;
import io.repsy.protocols.golang.protocol.facades.contracts.GoProtocolFacade;
import io.repsy.protocols.shared.auth.BasicAuthChallenge;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;

@DisplayName("AbstractGoUploadProtocolMethodHandler answers a refused upload with a text/plain 401")
class AbstractGoUploadProtocolMethodHandlerTest {

  private final GoProtocolFacade<UUID> facade = mock();
  private final ProtocolContext context = new ProtocolContext();
  private final MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/x/@v/v1.0.0");
  private AbstractGoUploadProtocolMethodHandler<UUID> handler;

  static class TestHandler extends AbstractGoUploadProtocolMethodHandler<UUID> {

    TestHandler(final GoProtocolFacade<UUID> facade) {
      super(mock(PathParser.class), facade, mock(GolangProtocolProvider.class));
    }
  }

  @BeforeEach
  void setUp() {
    this.handler = new TestHandler(this.facade);
  }

  @Test
  @DisplayName("the 401 has the challenge and the exception's message id as its text/plain body")
  void unauthorizedIsPlainText() throws Exception {
    doThrow(new UnAuthorizedException("deployTokenExpired"))
        .when(this.facade)
        .upload(any(), any(), anyLong());

    final var response = this.handler.handle(this.context, this.request, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
    assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
        .isEqualTo(BasicAuthChallenge.REPSY);
    assertThat(response.getBody()).isEqualTo("deployTokenExpired");
  }

  @Test
  @DisplayName("an exception with no message is the generic unAuthorized")
  void unauthorizedWithoutMessage() throws Exception {
    doThrow(new UnAuthorizedException(null)).when(this.facade).upload(any(), any(), anyLong());

    final var response = this.handler.handle(this.context, this.request, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).isEqualTo("unAuthorized");
  }

  @Test
  @DisplayName("an accepted upload is an empty 200")
  void acceptedUpload() throws Exception {
    final var response = this.handler.handle(this.context, this.request, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNull();
  }
}
