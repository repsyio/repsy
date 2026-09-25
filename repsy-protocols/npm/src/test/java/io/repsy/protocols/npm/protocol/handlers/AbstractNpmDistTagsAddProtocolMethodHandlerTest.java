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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.npm.protocol.facades.NpmProtocolFacade;
import io.repsy.protocols.npm.protocol.handlers.NpmHandlerTestSupport.FixedBaseParser;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * RPS-1362: yarn classic takes a dist-tag answer without an {@code ok} field for a failure, so the
 * add answers a JSON body, as the remove does.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNpmDistTagsAddProtocolMethodHandler")
class AbstractNpmDistTagsAddProtocolMethodHandlerTest {

  @Mock private NpmProtocolFacade facade;
  @Mock private NpmProtocolProvider provider;

  private static class TestHandler extends AbstractNpmDistTagsAddProtocolMethodHandler {
    TestHandler(
        final PathParser base, final NpmProtocolFacade facade, final NpmProtocolProvider provider) {
      super(base, facade, provider);
    }
  }

  private TestHandler handler(final String relativePath) {
    return new TestHandler(new FixedBaseParser(relativePath, true), this.facade, this.provider);
  }

  private static MockHttpServletRequest request(final String path, final String body) {
    final var request = NpmHandlerTestSupport.request("PUT", "/npm" + path);
    request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return request;
  }

  @Test
  @DisplayName("registers for PUT with WRITE metadata")
  void metadata() {
    final var handler = this.handler("/-/package/left-pad/dist-tags/next");

    verify(this.provider).registerMethodHandler(handler);
    assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.PUT);
    assertThat(handler.getProperties())
        .containsEntry("permission", Permission.WRITE)
        .containsEntry("writeOperation", true);
  }

  @Test
  @DisplayName("sets the tag and answers ok, the package and its tags as JSON")
  void answersOkAsJson() throws Exception {
    final var path = "/-/package/left-pad/dist-tags/next";
    final var context = NpmHandlerTestSupport.context(path);
    final var tags = new LinkedHashMap<String, String>();
    tags.put("latest", "1.0.0");
    tags.put("next", "2.0.0");
    when(this.facade.getMappedDistributionTags(context, null, "left-pad")).thenReturn(tags);

    final var response =
        this.handler(path)
            .handle(context, request(path, "\"2.0.0\""), new MockHttpServletResponse());

    verify(this.facade).addDistributionTag(context, null, "left-pad", "next", "\"2.0.0\"");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(response.getBody())
        .isEqualTo(Map.of("ok", true, "id", "left-pad", "dist-tags", tags));
  }

  @Test
  @DisplayName("names a scoped package with its scope in the id")
  void scopedId() throws Exception {
    final var path = "/-/package/@acme/widget/dist-tags/beta";
    final var context = NpmHandlerTestSupport.context(path);
    when(this.facade.getMappedDistributionTags(context, "acme", "widget"))
        .thenReturn(Map.of("beta", "1.0.0-beta.1"));

    final var response =
        this.handler(path)
            .handle(context, request(path, "\"1.0.0-beta.1\""), new MockHttpServletResponse());

    verify(this.facade).addDistributionTag(context, "acme", "widget", "beta", "\"1.0.0-beta.1\"");
    assertThat(response.getBody())
        .isEqualTo(
            Map.of("ok", true, "id", "@acme/widget", "dist-tags", Map.of("beta", "1.0.0-beta.1")));
  }

  @Test
  @DisplayName("keeps the field order ok, id, dist-tags")
  void fieldOrder() throws Exception {
    final var path = "/-/package/left-pad/dist-tags/next";

    final var response =
        this.handler(path)
            .handle(
                NpmHandlerTestSupport.context(path),
                request(path, "\"1.0.0\""),
                new MockHttpServletResponse());

    assertThat(new java.util.ArrayList<Object>(((Map<?, ?>) response.getBody()).keySet()))
        .isEqualTo(List.of("ok", "id", "dist-tags"));
  }

  @Test
  @DisplayName("answers 500 for a path the pattern does not match")
  void unmatchedPath() throws Exception {
    final ProtocolContext context = NpmHandlerTestSupport.context("/-/package/left-pad");

    final var response =
        this.handler("/-/package/left-pad")
            .handle(context, request("/-/package/left-pad", ""), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    verifyNoInteractions(this.facade);
  }
}
