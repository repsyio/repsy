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
package io.repsy.protocols.docker.protocol.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.docker.protocol.facades.DockerProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractDockerManifestDeleteProtocolMethodHandler")
class AbstractDockerManifestDeleteProtocolMethodHandlerTest {

  private static final String SHA256 = "sha256:" + "ab".repeat(32);
  private static final String SHA512 = "sha512:" + "cd".repeat(64);

  @Mock private PathParser basePathParser;
  @Mock private DockerProtocolFacade<UUID> dockerFacade;
  @Mock private DockerProtocolProvider provider;

  private static class TestHandler extends AbstractDockerManifestDeleteProtocolMethodHandler<UUID> {
    TestHandler(
        final PathParser parser,
        final DockerProtocolFacade<UUID> facade,
        final DockerProtocolProvider provider) {
      super(parser, facade, provider);
    }
  }

  private TestHandler handler() {
    return new TestHandler(this.basePathParser, this.dockerFacade, this.provider);
  }

  private static ProtocolContext contextFor(final String relativePath) {
    final var context = new ProtocolContext();
    context.addProperty(
        "urlProperties",
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName("images")
            .relativePath(new RelativePath(relativePath))
            .repoInfo(new BaseRepoInfo<UUID>())
            .build());
    return context;
  }

  private void basePathIs(final String relativePath) {
    when(this.basePathParser.parse(any())).thenReturn(Optional.of(contextFor(relativePath)));
  }

  @Test
  @DisplayName("registers itself, for DELETE, and needs MANAGE without being a write operation")
  void registersForDeleteWithManage() {
    final var handler = this.handler();

    verify(this.provider).registerMethodHandler(handler);
    assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.DELETE);
    assertThat(handler.getProperties())
        .containsEntry("permission", Permission.MANAGE)
        .containsEntry("writeOperation", false)
        .doesNotContainKey("skipPreProcessor")
        .doesNotContainKey("skipHeaderPreProcessor");
  }

  private static Stream<String> deletableReferences() {
    return Stream.of("latest", "v1.2.3", SHA256, SHA512);
  }

  private static Stream<String> malformedReferences() {
    return Stream.of(
        "-bad-tag",
        ".bad",
        "sha256:short",
        "sha384:" + "ab".repeat(48),
        "sha512:" + "cd".repeat(32));
  }

  private static Stream<String> otherPaths() {
    return Stream.of("/app/blobs/" + SHA256, "/app/blobs/uploads/abc", "/app", "/app/tags/list");
  }

  @ParameterizedTest
  @MethodSource("deletableReferences")
  @DisplayName("routes a DELETE of a manifest by a tag or a digest")
  void routesDeleteOfAManifest(final String reference) {
    this.basePathIs("/app/manifests/" + reference);

    final var parsed =
        this.handler().getPathParser().parse(new MockHttpServletRequest("DELETE", "/v2/x"));

    assertThat(parsed).isPresent();
  }

  @ParameterizedTest
  @ValueSource(strings = {"GET", "HEAD", "PUT", "POST"})
  @DisplayName("routes no other method, and does not even look the path up")
  void routesNoOtherMethod(final String method) {
    final var parsed =
        this.handler().getPathParser().parse(new MockHttpServletRequest(method, "/v2/x"));

    assertThat(parsed).isEmpty();
    verifyNoInteractions(this.basePathParser);
  }

  @ParameterizedTest
  @MethodSource("otherPaths")
  @DisplayName("routes no other path")
  void routesNoOtherPath(final String relativePath) {
    this.basePathIs(relativePath);

    final var parsed =
        this.handler().getPathParser().parse(new MockHttpServletRequest("DELETE", "/v2/x"));

    assertThat(parsed).isEmpty();
  }

  @Test
  @DisplayName("routes nothing when the base parser knows no such repo")
  void routesNothingForAnUnknownRepo() {
    when(this.basePathParser.parse(any())).thenReturn(Optional.empty());

    assertThat(this.handler().getPathParser().parse(new MockHttpServletRequest("DELETE", "/v2/x")))
        .isEmpty();
  }

  @ParameterizedTest
  @MethodSource("deletableReferences")
  @DisplayName("answers 202 Accepted with no body and hands image and reference to the facade")
  void answersAccepted(final String reference) {
    final var context = contextFor("/app/manifests/" + reference);

    final var response =
        this.handler().handle(context, new MockHttpServletRequest(), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getBody()).isNull();
    verify(this.dockerFacade).deleteManifest(context, "app", reference);
  }

  @ParameterizedTest
  @MethodSource("malformedReferences")
  @DisplayName("refuses a reference that is neither a tag nor a supported digest, before deleting")
  void refusesAMalformedReference(final String reference) {
    final var context = contextFor("/app/manifests/" + reference);

    assertThatThrownBy(
            () ->
                this.handler()
                    .handle(context, new MockHttpServletRequest(), new MockHttpServletResponse()))
        .isInstanceOf(BadRequestException.class);
    verifyNoInteractions(this.dockerFacade);
  }

  @Test
  @DisplayName("lets an unknown manifest or tag surface as the facade's not-found error")
  void propagatesNotFound() {
    final var context = contextFor("/app/manifests/" + SHA256);
    doThrow(new ItemNotFoundException("manifestNotFound"))
        .when(this.dockerFacade)
        .deleteManifest(context, "app", SHA256);

    assertThatThrownBy(
            () ->
                this.handler()
                    .handle(context, new MockHttpServletRequest(), new MockHttpServletResponse()))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("manifestNotFound");
  }

  @Test
  @DisplayName("answers 500 for a path it cannot have been routed for")
  void answersServerErrorForAPathItDoesNotMatch() {
    final var context = contextFor("/app/blobs/" + SHA256);

    final var response =
        this.handler().handle(context, new MockHttpServletRequest(), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    verifyNoInteractions(this.dockerFacade);
  }
}
