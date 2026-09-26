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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.ruby.protocol.RubyProtocolProvider;
import io.repsy.protocols.ruby.protocol.facades.contract.RubyProtocolFacade;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractRubySpecsIndexHandler")
class AbstractRubySpecsIndexHandlerTest {

  private static final String URL_PROPERTIES = "urlProperties";

  @Mock private PathParser basePathParser;
  @Mock private RubyProtocolFacade facade;
  @Mock private RubyProtocolProvider provider;

  private static final class TestHandler extends AbstractRubySpecsIndexHandler {

    TestHandler(
        final PathParser basePathParser,
        final RubyProtocolFacade facade,
        final RubyProtocolProvider provider) {
      super(basePathParser, facade, provider);
    }
  }

  private TestHandler handler() {
    return new TestHandler(this.basePathParser, this.facade, this.provider);
  }

  private static ProtocolContext contextFor(final String relativePath) {
    final var context = new ProtocolContext();
    final BaseUrlParserProperties<?, ?> urlProperties = mock(BaseUrlParserProperties.class);
    when(urlProperties.getRelativePath()).thenReturn(new RelativePath(relativePath));
    context.addProperty(URL_PROPERTIES, urlProperties);
    return context;
  }

  private static byte[] gunzip(final byte[] gzipped) throws Exception {
    try (final var in = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
      return in.readAllBytes();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"/specs.4.8.gz", "/latest_specs.4.8.gz", "/prerelease_specs.4.8.gz"})
  @DisplayName("names the download after the index file instead of f.txt (RPS-1442)")
  void namesTheIndexFile(final String path) {
    lenient().when(this.facade.getSpecs(any())).thenReturn(new byte[0]);
    lenient().when(this.facade.getLatestSpecs(any())).thenReturn(new byte[0]);
    lenient().when(this.facade.getPrereleaseSpecs(any())).thenReturn(new byte[0]);

    final var response =
        this.handler()
            .handle(contextFor(path), new MockHttpServletRequest(), new MockHttpServletResponse());

    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .isEqualTo("attachment; filename=\"" + path.substring(1) + "\"");
  }

  @Test
  @DisplayName("gzips the specs.4.8.gz payload and calls facade.getSpecs")
  void servesGzippedSpecs() throws Exception {
    final byte[] marshalPayload = {0x04, 0x08, 1, 2, 3};
    when(this.facade.getSpecs(any())).thenReturn(marshalPayload);

    final var response =
        this.handler()
            .handle(
                contextFor("/specs.4.8.gz"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    final var body = (byte[]) response.getBody();
    assertThat(body).isNotNull();
    assertThat(body[0]).isEqualTo((byte) 0x1f);
    assertThat(body[1]).isEqualTo((byte) 0x8b);
    assertThat(gunzip(body)).isEqualTo(marshalPayload);

    verify(this.facade).getSpecs(any());
    verify(this.facade, never()).getLatestSpecs(any());
    verify(this.facade, never()).getPrereleaseSpecs(any());
  }

  @Test
  @DisplayName("gzips the latest_specs.4.8.gz payload and calls facade.getLatestSpecs")
  void servesGzippedLatestSpecs() throws Exception {
    final byte[] marshalPayload = {0x04, 0x08, 4, 5, 6};
    when(this.facade.getLatestSpecs(any())).thenReturn(marshalPayload);

    final var response =
        this.handler()
            .handle(
                contextFor("/latest_specs.4.8.gz"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    final var body = (byte[]) response.getBody();
    assertThat(body).isNotNull();
    assertThat(body[0]).isEqualTo((byte) 0x1f);
    assertThat(body[1]).isEqualTo((byte) 0x8b);
    assertThat(gunzip(body)).isEqualTo(marshalPayload);

    verify(this.facade).getLatestSpecs(any());
    verify(this.facade, never()).getSpecs(any());
    verify(this.facade, never()).getPrereleaseSpecs(any());
  }

  @Test
  @DisplayName("gzips the prerelease_specs.4.8.gz payload and calls facade.getPrereleaseSpecs")
  void servesGzippedPrereleaseSpecs() throws Exception {
    final byte[] marshalPayload = {0x04, 0x08, 7, 8, 9};
    when(this.facade.getPrereleaseSpecs(any())).thenReturn(marshalPayload);

    final var response =
        this.handler()
            .handle(
                contextFor("/prerelease_specs.4.8.gz"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    final var body = (byte[]) response.getBody();
    assertThat(body).isNotNull();
    assertThat(body[0]).isEqualTo((byte) 0x1f);
    assertThat(body[1]).isEqualTo((byte) 0x8b);
    assertThat(gunzip(body)).isEqualTo(marshalPayload);

    verify(this.facade).getPrereleaseSpecs(any());
    verify(this.facade, never()).getSpecs(any());
    verify(this.facade, never()).getLatestSpecs(any());
  }

  @Test
  @DisplayName("path parser rejects a path outside the three specs index files")
  void pathParserRejectsUnknownPath() {
    final var request = new MockHttpServletRequest("GET", "/repo/versions");
    final var parsed = contextFor("/versions");
    when(this.basePathParser.parse(request)).thenReturn(Optional.of(parsed));

    assertThat(this.handler().getPathParser().parse(request)).isEmpty();
  }

  @Test
  @DisplayName("path parser rejects the extensionless specs.4.8 legacy path")
  void pathParserRejectsBareSpecsPath() {
    final var request = new MockHttpServletRequest("GET", "/repo/specs.4.8");
    final var parsed = contextFor("/specs.4.8");
    when(this.basePathParser.parse(request)).thenReturn(Optional.of(parsed));

    assertThat(this.handler().getPathParser().parse(request)).isEmpty();
  }

  @Test
  @DisplayName("path parser rejects a non-GET method without consulting the base parser")
  void pathParserRejectsNonGet() {
    final var request = new MockHttpServletRequest("POST", "/repo/specs.4.8.gz");

    assertThat(this.handler().getPathParser().parse(request)).isEmpty();

    verifyNoInteractions(this.basePathParser);
  }
}
