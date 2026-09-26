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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.npm.protocol.facades.NpmProtocolFacade;
import io.repsy.protocols.shared.auth.BasicAuthChallenge;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.Permission;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1424: the packument PUT of an {@code npm unpublish} ({@code PUT /pkg/-rev/<rev>}, RPS-1289)
 * removes a version, so it needs MANAGE, and hands the facade the package name without the {@code
 * /-rev/<rev>} tail. It is the only PUT it takes: a publish or a deprecate stays WRITE.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNpmPackageUnpublishProtocolMethodHandler (RPS-1424)")
class AbstractNpmPackageUnpublishProtocolMethodHandlerTest {

  @Mock private PathParser basePathParser;
  @Mock private NpmProtocolFacade facade;
  @Mock private NpmProtocolProvider provider;

  private static class TestHandler extends AbstractNpmPackageUnpublishProtocolMethodHandler {

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

  private AbstractNpmPackageUnpublishProtocolMethodHandler handler() {
    return new TestHandler(this.basePathParser, this.facade, new ObjectMapper(), this.provider);
  }

  private Optional<ProtocolContext> parse(final String method, final String relativePath) {
    final var request = new MockHttpServletRequest(method, "/npm" + relativePath);
    lenient()
        .when(this.basePathParser.parse(request))
        .thenReturn(Optional.of(context(relativePath)));

    return this.handler().getPathParser().parse(request);
  }

  @Test
  @DisplayName("needs MANAGE, so a USER account and a deploy token are refused before handle()")
  void needsManage() {
    assertThat(this.handler().getProperties())
        .containsEntry("permission", Permission.MANAGE)
        .containsEntry("writeOperation", true);
    assertThat(this.handler().getSupportedMethods()).containsExactly(HttpMethod.PUT);
  }

  @ParameterizedTest(name = "PUT {0} -> matches={1}")
  @CsvSource({
    "/left-pad/-rev/3-abc,                               true",
    "/@acme/left-pad/-rev/3-abc,                         true",
    "/left-pad,                                          false",
    "/@acme/left-pad,                                    false",
    "/left-pad/-/left-pad-1.0.0.tgz/-rev/3-abc,          false",
    "/@acme/left-pad/-/left-pad-1.0.0.tgz/-rev/3-abc,    false",
    "/left-pad/-rev/,                                    false",
    "/-/user/org.couchdb.user:bob,                       false",
    "/-/package/left-pad/dist-tags/next,                 false"
  })
  @DisplayName("getPathParser() takes only the packument PUT of an unpublish")
  void pathParser(final String relativePath, final boolean matches) {
    assertThat(this.parse("PUT", relativePath).isPresent()).isEqualTo(matches);
  }

  @Test
  @DisplayName("getPathParser() ignores the other methods on an unpublish path")
  void pathParserIgnoresOtherMethods() {
    assertThat(this.parse("DELETE", "/left-pad/-rev/3-abc")).isEmpty();
    assertThat(this.parse("GET", "/left-pad/-rev/3-abc")).isEmpty();
  }

  @Test
  @DisplayName("getPathParser() passes on a path the base parser does not know")
  void pathParserPassesOnAnUnknownPath() {
    final var request = new MockHttpServletRequest("PUT", "/other/left-pad/-rev/3-abc");
    when(this.basePathParser.parse(request)).thenReturn(Optional.empty());

    assertThat(this.handler().getPathParser().parse(request)).isEmpty();
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

  @ParameterizedTest(name = "PUT {0} unpublishes {2} of scope {1}, answers ok and the id {3}")
  @CsvSource(
      value = {
        "/left-pad/-rev/3-abc,       NULL, left-pad, left-pad",
        "/@acme/left-pad/-rev/3-abc, acme, left-pad, @acme/left-pad"
      },
      nullValues = "NULL")
  @DisplayName("handle() unpublishes with the package name that precedes /-rev/<rev>")
  void unpublishGetsThePackageNameWithoutTheRevision(
      final String relativePath, final String scope, final String name, final String id)
      throws Exception {

    final var response = this.put(relativePath, "{\"versions\":{}}");

    assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    assertThat(this.lastResult.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(this.lastResult.getBody()).isEqualTo(Map.of("ok", true, "id", id, "success", true));
    verify(this.facade)
        .unPublishPackageVersion(any(), eq(scope), eq(name), eq(Map.of("versions", Map.of())));
    verify(this.facade, never()).publishOrDeprecate(any(), any(), any(), any());
  }

  @Test
  @DisplayName("handle() answers a 401 challenge when the facade refuses the caller")
  void handleAnswersAChallengeWhenTheFacadeRefuses() throws Exception {
    when(this.facade.unPublishPackageVersion(any(), any(), any(), any()))
        .thenThrow(new UnAuthorizedException("unAuthorized"));

    final var response = this.put("/left-pad/-rev/3-abc", "{\"versions\":{}}");

    assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    assertThat(this.lastResult.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
        .isEqualTo(BasicAuthChallenge.REPSY);
  }

  @Test
  @DisplayName("handle() refuses a path the parser would not have matched")
  void handleRefusesANonRevPath() throws Exception {
    final var response = this.put("/left-pad", "{}");

    assertThat(response.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    verifyNoInteractions(this.facade);
  }
}
