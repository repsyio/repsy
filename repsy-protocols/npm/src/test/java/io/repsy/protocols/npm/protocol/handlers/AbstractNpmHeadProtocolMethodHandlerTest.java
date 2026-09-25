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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.npm.protocol.facades.NpmProtocolFacade;
import io.repsy.protocols.npm.protocol.handlers.NpmHandlerTestSupport.FixedBaseParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;

/** RPS-1358: a HEAD is answered like the GET of the same path, without the body. */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNpmHeadProtocolMethodHandler (RPS-1358)")
class AbstractNpmHeadProtocolMethodHandlerTest {

  @Mock private NpmProtocolProvider provider;
  @Mock private NpmProtocolFacade facade;

  private static class TestHandler extends AbstractNpmHeadProtocolMethodHandler {
    TestHandler(
        final PathParser base, final NpmProtocolFacade facade, final NpmProtocolProvider p) {
      super(base, facade, p);
    }
  }

  private HttpStatus head(final String relativePath) {
    final var handler =
        new TestHandler(new FixedBaseParser(relativePath, true), this.facade, this.provider);

    return HttpStatus.valueOf(
        handler
            .handle(
                NpmHandlerTestSupport.context(relativePath),
                NpmHandlerTestSupport.request("HEAD", "/npm" + relativePath),
                new MockHttpServletResponse())
            .getStatusCode()
            .value());
  }

  @Test
  @DisplayName("registers for HEAD")
  void registers() {
    final var handler =
        new TestHandler(new FixedBaseParser("/x", true), this.facade, this.provider);

    verify(this.provider).registerMethodHandler(handler);
    assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.HEAD);
  }

  @Test
  @DisplayName("is 200 for a package the repo has and 404 for one it does not")
  void package_() {
    when(this.facade.packageExists(any(), eq(null), eq("there"))).thenReturn(true);
    when(this.facade.packageExists(any(), eq(null), eq("gone"))).thenReturn(false);

    assertThat(this.head("/there")).isEqualTo(HttpStatus.OK);
    assertThat(this.head("/gone")).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("looks a scoped package up by its scope and its name")
  void scopedPackage() {
    when(this.facade.packageExists(any(), eq("acme"), eq("there"))).thenReturn(true);

    assertThat(this.head("/@acme/there")).isEqualTo(HttpStatus.OK);
    assertThat(this.head("/@acme/gone")).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("is 200 for a tarball the repo has and 404 for one it does not")
  void tarball() {
    when(this.facade.tarballExists(any(), eq(null), eq("demo"), eq("demo-1.0.0.tgz")))
        .thenReturn(true);
    when(this.facade.tarballExists(any(), eq(null), eq("demo"), eq("demo-2.0.0.tgz")))
        .thenReturn(false);

    assertThat(this.head("/demo/-/demo-1.0.0.tgz")).isEqualTo(HttpStatus.OK);
    assertThat(this.head("/demo/-/demo-2.0.0.tgz")).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("looks a scoped tarball up by its scope, its name and its file")
  void scopedTarball() {
    when(this.facade.tarballExists(any(), eq("acme"), eq("demo"), eq("demo-1.0.0.tgz")))
        .thenReturn(true);

    assertThat(this.head("/@acme/demo/-/demo-1.0.0.tgz")).isEqualTo(HttpStatus.OK);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/", "/-/ping", "/-/whoami", "/-/v1/search", "/-/package/demo/dist-tags"})
  @DisplayName("is 200 for the repo itself and for the registry endpoints, asking nothing")
  void notAPackage(final String relativePath) {
    assertThat(this.head(relativePath)).isEqualTo(HttpStatus.OK);

    verifyNoInteractions(this.facade);
  }
}
