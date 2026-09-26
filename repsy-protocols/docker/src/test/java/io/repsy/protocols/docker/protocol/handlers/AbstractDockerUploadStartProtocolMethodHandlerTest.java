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

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractDockerUploadStartProtocolMethodHandler")
class AbstractDockerUploadStartProtocolMethodHandlerTest {

  private static final String START_URI = "/v2/images/app/blobs/uploads/";

  @Mock private PathParser basePathParser;
  @Mock private DockerProtocolProvider provider;

  /** Mints a different id on every call, like the concrete handler's time-ordered UUIDs. */
  private static class TestHandler extends AbstractDockerUploadStartProtocolMethodHandler {

    TestHandler(final PathParser basePathParser, final DockerProtocolProvider provider) {
      super(basePathParser, provider);
    }

    @Override
    protected UUID getUuid() {
      return UUID.randomUUID();
    }
  }

  @AfterEach
  void clearRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  @DisplayName("the Location path and the Docker-Upload-UUID header name the same session id")
  void locationAndUploadUuidCarryTheSameSessionId() {
    final var request = new MockHttpServletRequest("POST", START_URI);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

    final var response =
        new TestHandler(this.basePathParser, this.provider)
            .handle(new ProtocolContext(), request, new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

    final var location = response.getHeaders().getFirst("Location");
    final var uploadUuid = response.getHeaders().getFirst("Docker-Upload-UUID");

    assertThat(uploadUuid).isNotNull();
    assertThat(location).endsWith(START_URI + uploadUuid);
    assertThat(UUID.fromString(uploadUuid)).isNotNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"sha256", "sha512"})
  @DisplayName("a digest-algorithm the registry can check starts the upload (RPS-1594)")
  void supportedDigestAlgorithmStartsTheUpload(final String algorithm) {
    final var request = new MockHttpServletRequest("POST", START_URI);
    request.setParameter("digest-algorithm", algorithm);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

    final var response =
        new TestHandler(this.basePathParser, this.provider)
            .handle(new ProtocolContext(), request, new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getHeaders().getFirst("Docker-Upload-UUID")).isNotNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"md5", "sha384", "SHA512", "sha512:", "", " "})
  @DisplayName("a digest-algorithm the registry cannot check is refused (RPS-1594)")
  void unsupportedDigestAlgorithmIsRefused(final String algorithm) {
    final var request = new MockHttpServletRequest("POST", START_URI);
    request.setParameter("digest-algorithm", algorithm);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    final var handler = new TestHandler(this.basePathParser, this.provider);
    final var context = new ProtocolContext();
    final var servletResponse = new MockHttpServletResponse();

    assertThatThrownBy(() -> handler.handle(context, request, servletResponse))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("dockerDigestAlgorithmUnsupported");
  }
}
