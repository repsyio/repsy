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
package io.repsy.protocols.ruby.protocol.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.ruby.protocol.RubyProtocolProvider;
import io.repsy.protocols.ruby.protocol.facades.contract.RubyProtocolFacade;
import io.repsy.protocols.shared.utils.SpooledUpload;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractRubyGemPublishHandler")
class AbstractRubyGemPublishHandlerTest {

  private static final long MAX_GEM_BYTES = 1_000;
  private static final byte[] GEM = "gem-bytes".repeat(50).getBytes(StandardCharsets.UTF_8);

  @Mock private PathParser basePathParser;
  @Mock private RubyProtocolFacade facade;
  @Mock private RubyProtocolProvider provider;

  private static class TestHandler extends AbstractRubyGemPublishHandler {

    TestHandler(
        final PathParser basePathParser,
        final RubyProtocolFacade facade,
        final RubyProtocolProvider provider) {
      super(basePathParser, facade, provider, MAX_GEM_BYTES);
    }
  }

  private TestHandler handler() {
    return new TestHandler(this.basePathParser, this.facade, this.provider);
  }

  private static MockHttpServletRequest push(final byte[] body) {
    final var request = new MockHttpServletRequest("POST", "/ruby/api/v1/gems");
    request.setContent(body);
    return request;
  }

  private static String sha256Of(final byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  @Test
  @DisplayName("hands the facade a spooled copy of the body, with its size and SHA-256")
  void handsFacadeSpooledBody() throws Exception {
    final var context = new ProtocolContext();
    context.addProperty("gemName", "demo");
    context.addProperty("gemVersion", "1.0.0");
    final var seen = new AtomicReference<byte[]>();
    final var size = new AtomicReference<Long>();
    final var sha256 = new AtomicReference<String>();
    doAnswer(
            invocation -> {
              final SpooledUpload gem = invocation.getArgument(1);
              try (final var in = gem.openStream()) {
                seen.set(in.readAllBytes());
              }
              size.set(gem.size());
              sha256.set(gem.sha256Hex());
              return null;
            })
        .when(this.facade)
        .publishGem(any(), any());

    final var response = this.handler().handle(context, push(GEM), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo("Successfully registered gem: demo (1.0.0)");
    assertThat(seen.get()).isEqualTo(GEM);
    assertThat(size.get()).isEqualTo(GEM.length);
    assertThat(sha256.get()).isEqualTo(sha256Of(GEM));
  }

  @Test
  @DisplayName("accepts a gem of exactly the limit")
  void acceptsGemAtLimit() throws Exception {
    final var context = new ProtocolContext();
    context.addProperty("gemName", "demo");
    context.addProperty("gemVersion", "1.0.0");

    final var response =
        this.handler()
            .handle(context, push(new byte[(int) MAX_GEM_BYTES]), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  @DisplayName("refuses a body that declares a length past the limit without reading it")
  void refusesDeclaredOversizedBody() throws Exception {
    final var request = push(new byte[(int) MAX_GEM_BYTES + 1]);

    assertThatThrownBy(
            () ->
                this.handler()
                    .handle(new ProtocolContext(), request, new MockHttpServletResponse()))
        .isInstanceOf(MaxUploadSizeExceededException.class);

    verify(this.facade, never()).publishGem(any(), any());
  }

  @Test
  @DisplayName("refuses a body that outgrows the limit while it is read")
  void refusesBodyThatOutgrowsLimit() throws Exception {
    final var request =
        new MockHttpServletRequest("POST", "/ruby/api/v1/gems") {
          @Override
          public long getContentLengthLong() {
            return -1;
          }
        };
    request.setContent(new byte[(int) MAX_GEM_BYTES + 1]);

    assertThatThrownBy(
            () ->
                this.handler()
                    .handle(new ProtocolContext(), request, new MockHttpServletResponse()))
        .isInstanceOf(MaxUploadSizeExceededException.class);

    verify(this.facade, never()).publishGem(any(), any());
  }

  @Test
  @DisplayName("answers 400 invalidGemFile when the gem cannot be read")
  void answersBadRequestOnIoFailure() throws Exception {
    doThrow(new IOException("broken")).when(this.facade).publishGem(any(), any());

    final var response =
        this.handler().handle(new ProtocolContext(), push(GEM), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isEqualTo("invalidGemFile");
  }
}
