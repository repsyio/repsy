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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.npm.protocol.facades.NpmProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNpmDistTagsRemoveProtocolMethodHandler")
class AbstractNpmDistTagsRemoveProtocolMethodHandlerTest {

  @Mock private PathParser basePathParser;
  @Mock private NpmProtocolFacade facade;
  @Mock private NpmProtocolProvider provider;

  private static class TestHandler extends AbstractNpmDistTagsRemoveProtocolMethodHandler {

    TestHandler(
        final PathParser basePathParser,
        final NpmProtocolFacade facade,
        final NpmProtocolProvider provider) {
      super(basePathParser, facade, provider);
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

  private AbstractNpmDistTagsRemoveProtocolMethodHandler handler() {
    return new TestHandler(this.basePathParser, this.facade, this.provider);
  }

  /** Runs the handler's path parser as the router would, on a path the base parser accepts. */
  private Optional<ProtocolContext> parse(final String method, final String relativePath) {
    final var request = new MockHttpServletRequest(method, "/npm" + relativePath);
    final var context = context(relativePath);
    when(this.basePathParser.parse(request)).thenReturn(Optional.of(context));

    return this.handler().getPathParser().parse(request);
  }

  @Test
  @DisplayName("registers itself with the provider and exposes DELETE with WRITE metadata")
  void metadata() {
    final var handler = this.handler();

    verify(this.provider).registerMethodHandler(handler);
    assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.DELETE);
    assertThat(handler.getProperties())
        .containsEntry("permission", Permission.WRITE)
        .containsEntry("writeOperation", true);
  }

  @Test
  @DisplayName("getPathParser() leaves other HTTP methods alone")
  void pathParserRejectsOtherMethods() {
    final var request = new MockHttpServletRequest("GET", "/npm/-/package/left-pad/dist-tags/next");

    assertThat(this.handler().getPathParser().parse(request)).isEmpty();
    verifyNoInteractions(this.basePathParser);
  }

  @Test
  @DisplayName("getPathParser() is empty when the base parser does not know the path")
  void pathParserFollowsTheBaseParser() {
    final var request = new MockHttpServletRequest("DELETE", "/nowhere");
    when(this.basePathParser.parse(request)).thenReturn(Optional.empty());

    assertThat(this.handler().getPathParser().parse(request)).isEmpty();
  }

  @ParameterizedTest(name = "{0} -> matches={1}")
  @CsvSource({
    "/-/package/left-pad/dist-tags/next,                true",
    "/-/package/@scope/left-pad/dist-tags/next,         true",
    "/-/package/left-pad/dist-tags/,                    false",
    "/-/package/left-pad/dist-tags/a/b,                 false",
    "/-/package/left-pad/dist-tags,                     false",
    "/-/package//dist-tags/next,                        false",
    "/-/package/left-pad/versions/next,                 false"
  })
  @DisplayName("getPathParser() matches DELETE /-/package/{package}/dist-tags/{tag}")
  void pathParserMatchesTheDistTagEndpoint(final String relativePath, final boolean matches) {
    final var result = this.parse("DELETE", relativePath);

    assertThat(result.isPresent()).isEqualTo(matches);
  }

  @Test
  @DisplayName("getPathParser() rejects a very long path a client controls in linear time")
  void pathParserIsLinearOnAHostilePath() {
    final var hostilePaths =
        new String[] {
          "/-/package/a" + "/dist-tags/b".repeat(20_000) + "/",
          "/-/package/" + "/dist-tags/".repeat(20_000) + "x/",
          "/-/package/" + "a".repeat(200_000),
          "/-/package/a/dist-tags/" + "b".repeat(200_000) + "/c"
        };

    for (final var path : hostilePaths) {
      assertTimeoutPreemptively(
          Duration.ofSeconds(1), () -> assertThat(this.parse("DELETE", path)).isEmpty());
    }
  }

  @Test
  @DisplayName("handle() removes the tag of an unscoped package")
  void removesTheTagOfAnUnscopedPackage() throws Exception {
    final var context = context("/-/package/left-pad/dist-tags/next");

    final var response =
        this.handler().handle(context, new MockHttpServletRequest(), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(this.facade).removeDistributionTag(context, null, "left-pad", "next");
  }

  @Test
  @DisplayName("handle() splits a scoped package name from its scope")
  void removesTheTagOfAScopedPackage() throws Exception {
    final var context = context("/-/package/@scope/left-pad/dist-tags/beta");

    this.handler().handle(context, new MockHttpServletRequest(), new MockHttpServletResponse());

    verify(this.facade).removeDistributionTag(context, "scope", "left-pad", "beta");
  }

  @Test
  @DisplayName("handle() answers 500 for a path the pattern does not match")
  void answersServerErrorForAnUnmatchedPath() throws Exception {
    final var response =
        this.handler()
            .handle(
                context("/-/package/left-pad"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    verifyNoInteractions(this.facade);
  }
}
