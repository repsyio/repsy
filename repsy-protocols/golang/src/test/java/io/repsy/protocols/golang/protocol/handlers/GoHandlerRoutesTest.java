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
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.golang.protocol.GolangProtocolProvider;
import io.repsy.protocols.golang.protocol.facades.contracts.GoProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Pins the registration of the Go handlers as it was before they were built from a {@code
 * HandlerRoute} (RPS-2057), and that the path parser the backend passes in is used unchanged.
 */
@DisplayName("Go handlers keep their methods and properties")
class GoHandlerRoutesTest {

  private static final ProtocolContext CONTEXT = new ProtocolContext();
  private static final PathParser PARSER = _ -> Optional.of(CONTEXT);
  private static final GoProtocolFacade<?> FACADE = mock(GoProtocolFacade.class);
  private static final GolangProtocolProvider PROVIDER = mock(GolangProtocolProvider.class);

  private static <T extends ProtocolMethodHandler> T handler(final Class<T> type) {
    return mock(
        type,
        withSettings().useConstructor(PARSER, FACADE, PROVIDER).defaultAnswer(CALLS_REAL_METHODS));
  }

  private static void assertRoute(
      final ProtocolMethodHandler handler,
      final HttpMethod method,
      final Map<String, Object> properties) {
    assertThat(handler.getSupportedMethods()).containsExactly(method);
    assertThat(handler.getProperties()).isEqualTo(properties);
    assertThat(handler.getPathParser().parse(new MockHttpServletRequest(method.name(), "/x")))
        .containsSame(CONTEXT);
  }

  @Test
  @DisplayName("download names itself in the method property")
  void download() {
    assertRoute(
        handler(AbstractGoDownloadProtocolMethodHandler.class),
        HttpMethod.GET,
        Map.of("permission", Permission.READ, "writeOperation", false, "method", "download"));
  }

  @Test
  @DisplayName("upload names itself in the method property")
  void upload() {
    assertRoute(
        handler(AbstractGoUploadProtocolMethodHandler.class),
        HttpMethod.PUT,
        Map.of("permission", Permission.WRITE, "writeOperation", true, "method", "upload"));
  }

  @Test
  @DisplayName("download also answers HEAD, a read that is not counted as a download")
  void head() {
    final var h = handler(AbstractGoDownloadProtocolMethodHandler.class);
    assertThat(h.answersHead()).isTrue();
    assertThat(h.getHeadProperties())
        .isEqualTo(
            Map.of(
                "permission",
                Permission.READ,
                "writeOperation",
                false,
                "skipUsagePostProcessor",
                true));
  }
}
