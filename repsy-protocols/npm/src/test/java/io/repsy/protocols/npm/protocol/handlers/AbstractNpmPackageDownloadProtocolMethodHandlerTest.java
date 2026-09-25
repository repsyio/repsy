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
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.npm.protocol.facades.NpmProtocolFacade;
import io.repsy.protocols.npm.protocol.handlers.NpmHandlerTestSupport.FixedBaseParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/** RPS-1363: a tarball is offered as a download under its own name. */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNpmPackageDownloadProtocolMethodHandler (RPS-1363)")
class AbstractNpmPackageDownloadProtocolMethodHandlerTest {

  @Mock private NpmProtocolProvider provider;
  @Mock private NpmProtocolFacade facade;

  private static class TestHandler extends AbstractNpmPackageDownloadProtocolMethodHandler {
    TestHandler(
        final PathParser base, final NpmProtocolFacade facade, final NpmProtocolProvider p) {
      super(base, facade, p);
    }
  }

  private org.springframework.http.ResponseEntity<Object> download(
      final String relativePath, final String scope, final String file) throws Exception {
    when(this.facade.getTarball(any(), eq(scope), eq("demo"), eq(file)))
        .thenReturn(new ByteArrayResource(new byte[] {1}));

    return new TestHandler(new FixedBaseParser(relativePath, true), this.facade, this.provider)
        .handle(
            NpmHandlerTestSupport.context(relativePath),
            NpmHandlerTestSupport.request("GET", "/npm" + relativePath),
            new MockHttpServletResponse());
  }

  @Test
  @DisplayName("names the tarball as an attachment, not \"f.txt\" inline")
  void namesTheTarball() throws Exception {
    final var response = this.download("/demo/-/demo-1.0.0.tgz", null, "demo-1.0.0.tgz");

    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .isEqualTo("attachment; filename=\"demo-1.0.0.tgz\"");
  }

  @Test
  @DisplayName("names a scoped tarball by its bare file name, without the scope")
  void namesAScopedTarball() throws Exception {
    final var response = this.download("/@acme/demo/-/demo-1.0.0.tgz", "acme", "demo-1.0.0.tgz");

    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .isEqualTo("attachment; filename=\"demo-1.0.0.tgz\"");
  }

  @Test
  @DisplayName("drops a scope the client put before the file name")
  void dropsAScopeInTheFileName() throws Exception {
    final var response =
        this.download("/@acme/demo/-/@acme/demo-1.0.0.tgz", "acme", "@acme/demo-1.0.0.tgz");

    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .isEqualTo("attachment; filename=\"demo-1.0.0.tgz\"");
  }
}
