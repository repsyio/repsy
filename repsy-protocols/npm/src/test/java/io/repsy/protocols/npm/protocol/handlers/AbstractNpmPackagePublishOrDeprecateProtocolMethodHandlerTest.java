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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.npm.protocol.facades.NpmProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1289: the PUT handler tells a publish or a deprecate ({@code PUT /pkg}) from the packument
 * PUT of an {@code npm unpublish} ({@code PUT /pkg/-rev/<rev>}) and hands the facade the package
 * name without the {@code /-rev/<rev>} tail.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNpmPackagePublishOrDeprecateProtocolMethodHandler (RPS-1289)")
class AbstractNpmPackagePublishOrDeprecateProtocolMethodHandlerTest {

  @Mock private PathParser basePathParser;
  @Mock private NpmProtocolFacade facade;
  @Mock private NpmProtocolProvider provider;

  private static class TestHandler
      extends AbstractNpmPackagePublishOrDeprecateProtocolMethodHandler {

    TestHandler(
        final PathParser basePathParser,
        final NpmProtocolFacade facade,
        final ObjectMapper objectMapper,
        final NpmProtocolProvider provider) {
      super(basePathParser, facade, objectMapper, provider);
    }
  }

  private static ProtocolContext context(final String relativePath) {
    final var repoInfo = new BaseRepoInfo<UUID>();
    repoInfo.setName("npm");
    final var context = new ProtocolContext();
    context.addProperty(
        "urlProperties",
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName("npm")
            .relativePath(new RelativePath(relativePath))
            .repoInfo(repoInfo)
            .build());
    return context;
  }

  private AbstractNpmPackagePublishOrDeprecateProtocolMethodHandler handler() {
    return new TestHandler(this.basePathParser, this.facade, new ObjectMapper(), this.provider);
  }

  private Optional<ProtocolContext> parse(final String relativePath) {
    final var request = new MockHttpServletRequest("PUT", "/npm" + relativePath);
    when(this.basePathParser.parse(request)).thenReturn(Optional.of(context(relativePath)));

    return this.handler().getPathParser().parse(request);
  }

  @ParameterizedTest(name = "PUT {0} -> matches={1}")
  @CsvSource({
    "/left-pad,                                          true",
    "/@acme/left-pad,                                    true",
    "/left-pad/-rev/3-abc,                               true",
    "/@acme/left-pad/-rev/3-abc,                         true",
    "/left-pad/-/left-pad-1.0.0.tgz/-rev/3-abc,          false",
    "/@acme/left-pad/-/left-pad-1.0.0.tgz/-rev/3-abc,    false",
    "/left-pad/-rev/,                                    false",
    "/-/user/org.couchdb.user:bob,                       false",
    "/-/package/left-pad/dist-tags/next,                 false"
  })
  @DisplayName("getPathParser() takes the package and unpublish packument PUTs, not tarball ones")
  void pathParser(final String relativePath, final boolean matches) {
    assertThat(this.parse(relativePath).isPresent()).isEqualTo(matches);
  }

  private MockHttpServletResponse put(final String relativePath, final String body)
      throws Exception {

    final var request = new MockHttpServletRequest("PUT", "/npm" + relativePath);
    request.setContent(body.getBytes());
    final var response = new MockHttpServletResponse();

    final var result = this.handler().handle(context(relativePath), request, response);
    response.setStatus(result.getStatusCode().value());
    this.lastResult = result;

    return response;
  }

  private ResponseEntity<Object> lastResult;

  @ParameterizedTest(name = "PUT {0} answers ok and the id {1} as JSON")
  @CsvSource({
    "/left-pad,                    left-pad",
    "/@acme/left-pad,              @acme/left-pad",
    "/left-pad/-rev/3-abc,         left-pad",
    "/@acme/left-pad/-rev/3-abc,   @acme/left-pad"
  })
  @DisplayName("handle() answers a JSON body, not an empty 200 (RPS-1390)")
  void answersJson(final String relativePath, final String id) throws Exception {
    this.put(relativePath, "{\"versions\":{}}");

    assertThat(this.lastResult.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(this.lastResult.getBody()).isEqualTo(Map.of("ok", true, "id", id, "success", true));
  }

  @ParameterizedTest(name = "PUT {0} unpublishes {2} of scope {1}")
  @CsvSource(
      value = {
        "/left-pad/-rev/3-abc,       NULL, left-pad",
        "/@acme/left-pad/-rev/3-abc, acme, left-pad"
      },
      nullValues = "NULL")
  @DisplayName("handle() unpublishes with the package name that precedes /-rev/<rev>")
  void unpublishGetsThePackageNameWithoutTheRevision(
      final String relativePath, final String scope, final String name) throws Exception {

    final var response = this.put(relativePath, "{\"versions\":{}}");

    assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    verify(this.facade)
        .unPublishPackageVersion(any(), eq(scope), eq(name), eq(Map.of("versions", Map.of())));
  }

  @ParameterizedTest(name = "PUT {0} publishes {2} of scope {1}")
  @CsvSource(
      value = {"/left-pad, NULL, left-pad", "/@acme/left-pad, acme, left-pad"},
      nullValues = "NULL")
  @DisplayName("handle() keeps publishing and deprecating on the bare package path")
  void publishKeepsThePackagePath(final String relativePath, final String scope, final String name)
      throws Exception {

    final var response = this.put(relativePath, "{\"versions\":{}}");

    assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    verify(this.facade).publishOrDeprecate(any(), eq(scope), eq(name), any());
    verify(this.facade, never()).unPublishPackageVersion(any(), any(), any(), any());
  }

  @Test
  @DisplayName("handle() refuses a path the parser would not have matched")
  void handleRefusesANonPackagePath() throws Exception {
    final var response = this.put("", "{}");

    assertThat(response.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    verifyNoInteractions(this.facade);
  }
}
