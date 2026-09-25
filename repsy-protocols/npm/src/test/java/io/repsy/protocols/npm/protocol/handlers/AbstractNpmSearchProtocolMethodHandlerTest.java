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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.npm.protocol.handlers.NpmHandlerTestSupport.FixedBaseParser;
import io.repsy.protocols.npm.shared.search.NpmSearchQuery;
import io.repsy.protocols.npm.shared.search.NpmSearchResult;
import io.repsy.protocols.npm.shared.search.NpmSearchService;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNpmSearchProtocolMethodHandler")
class AbstractNpmSearchProtocolMethodHandlerTest {

  @Mock private NpmSearchService<UUID> service;
  @Mock private NpmProtocolProvider provider;

  private static class TestHandler extends AbstractNpmSearchProtocolMethodHandler<UUID> {
    TestHandler(
        final PathParser base, final NpmSearchService<UUID> service, final NpmProtocolProvider p) {
      super(base, service, p);
    }
  }

  private TestHandler handler() {
    return new TestHandler(new FixedBaseParser("/-/v1/search", true), this.service, this.provider);
  }

  @Test
  @DisplayName("registers for GET and reads; writeOperation is present and false")
  void metadata() {
    final var handler = this.handler();

    verify(this.provider).registerMethodHandler(handler);
    assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.GET);
    assertThat(handler.getProperties())
        .containsEntry("permission", Permission.READ)
        .containsEntry("writeOperation", false)
        .containsEntry("skipUsagePostProcessor", true);
  }

  @Test
  @DisplayName("claims GET /-/v1/search and nothing else")
  void parser() {
    final var parser = this.handler().getPathParser();

    assertThat(parser.parse(NpmHandlerTestSupport.request("GET", "/npm/-/v1/search"))).isPresent();
    assertThat(parser.parse(NpmHandlerTestSupport.request("GET", "/npm/-/v1/search/x"))).isEmpty();
    assertThat(parser.parse(NpmHandlerTestSupport.request("GET", "/npm/-/v1/login"))).isEmpty();
    assertThat(parser.parse(NpmHandlerTestSupport.request("POST", "/npm/-/v1/search"))).isEmpty();
  }

  @Test
  @DisplayName("hands the parsed query parameters to the search")
  void searches() throws Exception {
    final var result = NpmSearchResult.empty(Instant.now());
    when(this.service.search(any(), any())).thenReturn(result);
    final var request = NpmHandlerTestSupport.request("GET", "/npm/-/v1/search");
    request.setParameter("text", "Left-Pad scope:acme");
    request.setParameter("size", "500");
    request.setParameter("from", "99999999999");

    final var response =
        this.handler()
            .handle(
                NpmHandlerTestSupport.context("/-/v1/search"),
                request,
                new MockHttpServletResponse());

    final var captor = ArgumentCaptor.forClass(NpmSearchQuery.class);
    verify(this.service).search(any(), captor.capture());
    assertThat(captor.getValue().terms()).containsExactly("left-pad");
    assertThat(captor.getValue().scope()).isEqualTo("acme");
    assertThat(captor.getValue().size()).isEqualTo(250);
    assertThat(captor.getValue().from()).isEqualTo(Integer.MAX_VALUE);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(response.getBody()).isSameAs(result);
  }

  @Test
  @DisplayName("refuses a size or from that is no whole number instead of taking a default")
  void refusesJunk() {
    final var request = NpmHandlerTestSupport.request("GET", "/npm/-/v1/search");
    request.setParameter("size", "many");

    assertThatThrownBy(
            () ->
                this.handler()
                    .handle(
                        NpmHandlerTestSupport.context("/-/v1/search"),
                        request,
                        new MockHttpServletResponse()))
        .isInstanceOf(BadRequestException.class);
    verifyNoInteractions(this.service);
  }

  @Test
  @DisplayName("takes the defaults when no parameter is given")
  void defaults() throws Exception {
    when(this.service.search(any(), any())).thenReturn(NpmSearchResult.empty(Instant.now()));

    this.handler()
        .handle(
            NpmHandlerTestSupport.context("/-/v1/search"),
            NpmHandlerTestSupport.request("GET", "/npm/-/v1/search"),
            new MockHttpServletResponse());

    final var captor = ArgumentCaptor.forClass(NpmSearchQuery.class);
    verify(this.service).search(any(), captor.capture());
    assertThat(captor.getValue().terms()).isEmpty();
    assertThat(captor.getValue().size()).isEqualTo(20);
    assertThat(captor.getValue().from()).isZero();
  }
}
