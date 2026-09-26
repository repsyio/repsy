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
package io.repsy.os.server.protocols.golang.protocol.handlers;

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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * RPS-1450: the Go upload handler answers a refused upload with the same text/plain 401 body as
 * {@code GolangAuthPreProcessor}, the message being the one in {@code messages.properties}.
 */
@DisplayName("GolangUploadProtocolMethodHandler")
class GolangUploadProtocolMethodHandlerTest {

  private final GoProtocolFacade<UUID> facade = mock();
  private GolangUploadProtocolMethodHandler handler;

  @BeforeEach
  void setUp() {
    final var messages = new ResourceBundleMessageSource();
    messages.setBasename("messages");
    messages.setUseCodeAsDefaultMessage(false);

    this.handler =
        new GolangUploadProtocolMethodHandler(
            mock(PathParser.class), this.facade, mock(GolangProtocolProvider.class), messages);
  }

  @Test
  @DisplayName("a refused upload is a 401 whose text/plain body is the resolved message")
  void unauthorizedBodyIsTheMessage() throws Exception {
    doThrow(new UnAuthorizedException("unAuthorized"))
        .when(this.facade)
        .upload(any(), any(), anyLong());

    final var response =
        this.handler.handle(
            new ProtocolContext(), new MockHttpServletRequest("PUT", "/x/@v/v1.0.0"), null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
    assertThat(response.getBody())
        .isEqualTo(
            "The credentials are missing, invalid or expired, or they do not allow this action.");
  }

  @Test
  @DisplayName("a message id nobody defined is its own text")
  void unknownIdIsItsOwnText() {
    assertThat(this.handler.unauthorizedText("noSuchMessage")).isEqualTo("noSuchMessage");
    assertThat(this.handler.unauthorizedText(null))
        .isEqualTo(this.handler.unauthorizedText("unAuthorized"));
  }
}
