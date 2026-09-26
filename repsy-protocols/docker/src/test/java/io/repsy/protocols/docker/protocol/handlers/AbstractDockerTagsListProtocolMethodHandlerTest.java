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
import io.repsy.protocols.docker.shared.tag.dtos.TagListResponse;
import io.repsy.protocols.docker.shared.tag.dtos.TagPage;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.util.List;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractDockerTagsListProtocolMethodHandler")
class AbstractDockerTagsListProtocolMethodHandlerTest {

  private static final String REQUEST_URI = "/v2/images/app/tags/list";

  @Mock private PathParser basePathParser;
  @Mock private DockerProtocolFacade<UUID> dockerFacade;
  @Mock private DockerProtocolProvider provider;

  private static class TestHandler extends AbstractDockerTagsListProtocolMethodHandler<UUID> {
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

  private static MockHttpServletRequest requestWith(final String... nameValuePairs) {
    final var request = new MockHttpServletRequest("GET", REQUEST_URI);
    for (var i = 0; i < nameValuePairs.length; i += 2) {
      request.addParameter(nameValuePairs[i], nameValuePairs[i + 1]);
    }
    return request;
  }

  private void basePathIs(final String relativePath) {
    when(this.basePathParser.parse(any())).thenReturn(Optional.of(contextFor(relativePath)));
  }

  @Test
  @DisplayName("registers itself, for GET, and needs READ without being a write operation")
  void registersForGetWithRead() {
    final var handler = this.handler();

    verify(this.provider).registerMethodHandler(handler);
    assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.GET);
    assertThat(handler.getProperties())
        .containsEntry("permission", Permission.READ)
        .containsEntry("writeOperation", false);
  }

  @Test
  @DisplayName("routes GET of <image>/tags/list")
  void routesTheTagList() {
    this.basePathIs("/app/tags/list");

    assertThat(
            this.handler().getPathParser().parse(new MockHttpServletRequest("GET", "/v2/images")))
        .isPresent();
  }

  private static Stream<String> otherPaths() {
    return Stream.of(
        "/app/tags/list/x", "/app/tags", "/tags/list", "/app/manifests/latest", "/a/b/tags/list");
  }

  @ParameterizedTest
  @MethodSource("otherPaths")
  @DisplayName("routes no other path")
  void routesNoOtherPath(final String relativePath) {
    this.basePathIs(relativePath);

    assertThat(
            this.handler().getPathParser().parse(new MockHttpServletRequest("GET", "/v2/images")))
        .isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"HEAD", "PUT", "POST", "DELETE"})
  @DisplayName("routes no other method, and does not even look the path up")
  void routesNoOtherMethod(final String method) {
    assertThat(this.handler().getPathParser().parse(new MockHttpServletRequest(method, "/v2/x")))
        .isEmpty();
    verifyNoInteractions(this.basePathParser);
  }

  @Test
  @DisplayName("routes nothing when the base parser knows no such repo")
  void routesNothingForAnUnknownRepo() {
    when(this.basePathParser.parse(any())).thenReturn(Optional.empty());

    assertThat(this.handler().getPathParser().parse(new MockHttpServletRequest("GET", "/v2/x")))
        .isEmpty();
  }

  @Test
  @DisplayName("answers the repo-qualified name and the tags, with no Link on the last page")
  void answersTheTags() {
    final var context = contextFor("/app/tags/list");
    when(this.dockerFacade.listTags(context, "app", null, null))
        .thenReturn(new TagPage(List.of("latest", "v1"), false));

    final var response =
        this.handler().handle(context, requestWith(), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/json");
    assertThat(response.getHeaders().containsHeader(HttpHeaders.LINK)).isFalse();
    assertThat(response.getBody())
        .isEqualTo(new TagListResponse("images/app", List.of("latest", "v1")));
  }

  @Test
  @DisplayName("hands n and last to the facade, and links to the next page after the last tag")
  void linksToTheNextPage() {
    final var context = contextFor("/app/tags/list");
    when(this.dockerFacade.listTags(context, "app", 2, "a"))
        .thenReturn(new TagPage(List.of("b", "c.d"), true));

    final var response =
        this.handler()
            .handle(context, requestWith("n", "2", "last", "a"), new MockHttpServletResponse());

    assertThat(response.getHeaders().getFirst(HttpHeaders.LINK))
        .isEqualTo("<" + REQUEST_URI + "?n=2&last=c.d>; rel=\"next\"");
  }

  @Test
  @DisplayName("n=0 is an empty page with no Link")
  void zeroIsAnEmptyPage() {
    final var context = contextFor("/app/tags/list");
    when(this.dockerFacade.listTags(context, "app", 0, null))
        .thenReturn(new TagPage(List.of(), false));

    final var response =
        this.handler().handle(context, requestWith("n", "0"), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(((TagListResponse) response.getBody()).tags()).isEmpty();
    assertThat(response.getHeaders().containsHeader(HttpHeaders.LINK)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"abc", "-1", "", "1.5", "99999999999"})
  @DisplayName("refuses an n that is not a non-negative integer, before listing")
  void refusesAnInvalidN(final String n) {
    final var context = contextFor("/app/tags/list");

    assertThatThrownBy(
            () ->
                this.handler().handle(context, requestWith("n", n), new MockHttpServletResponse()))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("paginationNumberInvalid");
    verifyNoInteractions(this.dockerFacade);
  }

  @Test
  @DisplayName("lets an unknown image surface as the facade's not-found error")
  void propagatesNotFound() {
    final var context = contextFor("/app/tags/list");
    when(this.dockerFacade.listTags(context, "app", null, null))
        .thenThrow(new ItemNotFoundException("imageNotFound"));

    assertThatThrownBy(
            () -> this.handler().handle(context, requestWith(), new MockHttpServletResponse()))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("imageNotFound");
  }

  @Test
  @DisplayName("answers 500 for a path it cannot have been routed for")
  void answersServerErrorForAPathItDoesNotMatch() {
    final var response =
        this.handler()
            .handle(
                contextFor("/app/manifests/latest"), requestWith(), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    verifyNoInteractions(this.dockerFacade);
  }
}
