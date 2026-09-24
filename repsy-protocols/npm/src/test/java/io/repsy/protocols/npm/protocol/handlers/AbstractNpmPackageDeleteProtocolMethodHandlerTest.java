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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * RPS-1289: the DELETE handler tells the delete of a whole package ({@code DELETE /pkg/-rev/<rev>})
 * from the tarball request that ends an {@code npm unpublish} ({@code DELETE
 * /pkg/-/pkg-1.0.0.tgz/-rev/<rev>}), which used to delete "package" {@code pkg/-/pkg-1.0.0.tgz}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNpmPackageDeleteProtocolMethodHandler (RPS-1289)")
class AbstractNpmPackageDeleteProtocolMethodHandlerTest {

  @Mock private PathParser basePathParser;
  @Mock private NpmProtocolFacade facade;
  @Mock private NpmProtocolProvider provider;

  private static class TestHandler extends AbstractNpmPackageDeleteProtocolMethodHandler {

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

  private AbstractNpmPackageDeleteProtocolMethodHandler handler() {
    return new TestHandler(this.basePathParser, this.facade, this.provider);
  }

  private Optional<ProtocolContext> parse(final String relativePath) {
    final var request = new MockHttpServletRequest("DELETE", "/npm" + relativePath);
    when(this.basePathParser.parse(request)).thenReturn(Optional.of(context(relativePath)));

    return this.handler().getPathParser().parse(request);
  }

  @ParameterizedTest(name = "DELETE {0} -> matches={1}")
  @CsvSource({
    "/left-pad/-rev/3-abc,                               true",
    "/@acme/left-pad/-rev/3-abc,                         true",
    "/left-pad/-/left-pad-1.0.0.tgz/-rev/3-abc,          true",
    "/@acme/left-pad/-/left-pad-1.0.0.tgz/-rev/3-abc,    true",
    "/left-pad,                                          false",
    "/left-pad/-rev/,                                    false",
    "/-/package/left-pad/dist-tags/next,                 false"
  })
  @DisplayName("getPathParser() takes the -rev deletes of a package and of a tarball")
  void pathParser(final String relativePath, final boolean matches) {
    assertThat(this.parse(relativePath).isPresent()).isEqualTo(matches);
  }

  private MockHttpServletResponse delete(final String relativePath) throws Exception {
    final var request = new MockHttpServletRequest("DELETE", "/npm" + relativePath);
    final var response = new MockHttpServletResponse();

    final var result = this.handler().handle(context(relativePath), request, response);
    response.setStatus(result.getStatusCode().value());

    return response;
  }

  @ParameterizedTest(name = "DELETE {0} deletes {2} of scope {1}")
  @CsvSource(
      value = {
        "/left-pad/-rev/3-abc,       NULL, left-pad",
        "/@acme/left-pad/-rev/3-abc, acme, left-pad"
      },
      nullValues = "NULL")
  @DisplayName("handle() deletes the whole package on the packument path")
  void deletesThePackage(final String relativePath, final String scope, final String name)
      throws Exception {

    assertThat(this.delete(relativePath).getStatus()).isEqualTo(HttpStatus.OK.value());

    verify(this.facade).deletePackage(any(), eq(scope), eq(name));
    verify(this.facade, never()).deletePackageTarball(any(), any(), any(), any());
  }

  @ParameterizedTest(name = "DELETE {0} ends the unpublish of {2} of scope {1}")
  @CsvSource(
      value = {
        "/left-pad/-/left-pad-1.0.0.tgz/-rev/3-abc,       NULL, left-pad",
        "/@acme/left-pad/-/left-pad-1.0.0.tgz/-rev/3-abc, acme, left-pad"
      },
      nullValues = "NULL")
  @DisplayName("handle() never deletes the package on a tarball path")
  void tarballPathDoesNotDeleteThePackage(
      final String relativePath, final String scope, final String name) throws Exception {

    assertThat(this.delete(relativePath).getStatus()).isEqualTo(HttpStatus.OK.value());

    verify(this.facade).deletePackageTarball(any(), eq(scope), eq(name), eq("left-pad-1.0.0.tgz"));
    verify(this.facade, never()).deletePackage(any(), any(), any());
  }

  @Test
  @DisplayName("handle() refuses a path the parser would not have matched")
  void handleRefusesANonRevPath() throws Exception {
    assertThat(this.delete("/left-pad").getStatus())
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());

    verifyNoInteractions(this.facade);
  }
}
