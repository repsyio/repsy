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

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.docker.protocol.facades.DockerProtocolFacade;
import io.repsy.protocols.docker.shared.tag.dtos.TagListResponse;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * {@code GET /v2/<repo>/<image>/tags/list} (RPS-1489): the tags of an image, in lexical order, as
 * the distribution spec describes them. {@code crane ls}, {@code skopeo list-tags}, {@code regctl
 * tag ls} and the tag discovery of Renovate and of {@code skopeo inspect} all depend on it.
 *
 * <p>{@code n} limits the page and {@code last} is the tag it starts after. When more tags follow,
 * the answer carries a {@code Link: <...?n=N&last=T>; rel="next"} header that a client follows
 * until it is gone. Like the manifest pull it needs {@link Permission#READ}: a public repo answers
 * anonymously, a private one challenges with a {@code pull} scope.
 */
public abstract class AbstractDockerTagsListProtocolMethodHandler<ID>
    extends AbstractFacadeProtocolMethodHandler<DockerProtocolFacade<ID>> {

  private static final Pattern TAGS_LIST_PATTERN = Pattern.compile("^/([^/]+)/tags/list$");
  private static final String LIMIT_PARAMETER = "n";
  private static final String LAST_PARAMETER = "last";

  public AbstractDockerTagsListProtocolMethodHandler(
      final PathParser basePathParser,
      final DockerProtocolFacade<ID> dockerFacade,
      final DockerProtocolProvider provider) {
    super(
        HandlerRoute.of(Permission.READ, HttpMethod.GET)
            .writeOperation(false)
            .path(TAGS_LIST_PATTERN.asMatchPredicate()),
        basePathParser,
        dockerFacade,
        provider);
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    final var relativePath = ProtocolContextUtils.getRelativePath(context).getPath();
    final var matcher = TAGS_LIST_PATTERN.matcher(relativePath);

    if (!matcher.matches()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    final var imageName = matcher.group(1);
    final var limit = parseLimit(request.getParameter(LIMIT_PARAMETER));
    final var last = request.getParameter(LAST_PARAMETER);

    final var page = this.facade.listTags(context, imageName, limit, last);

    final var repoPath = ProtocolContextUtils.getUrlProperties(context).getRepoPath();
    final var body = new TagListResponse(repoPath + "/" + imageName, page.tags());

    final var answer =
        ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

    if (page.hasMore() && limit != null) {
      answer.header(
          HttpHeaders.LINK,
          nextLink(request.getRequestURI(), limit, page.tags().get(page.tags().size() - 1)));
    }

    return answer.body(body);
  }

  /**
   * A relative reference, as the distribution spec allows: the client resolves it against the URL
   * it called, so a proxy in front of the registry cannot make it point at an address the client
   * cannot reach.
   */
  private static String nextLink(final String requestUri, final int limit, final String last) {

    return "<%s?%s=%d&%s=%s>; rel=\"next\""
        .formatted(
            requestUri,
            LIMIT_PARAMETER,
            limit,
            LAST_PARAMETER,
            URLEncoder.encode(last, StandardCharsets.UTF_8));
  }

  /**
   * Reads {@code n}: a non-negative integer, or absent for every tag. Anything else names no number
   * of results, which the distribution reference registry answers with 400 {@code
   * PAGINATION_NUMBER_INVALID}.
   */
  private static @Nullable Integer parseLimit(final @Nullable String value) {

    if (value == null) {
      return null;
    }

    try {
      final var limit = Integer.parseInt(value);

      if (limit < 0) {
        throw new BadRequestException(ProtocolErrorCodes.PAGINATION_NUMBER_INVALID);
      }

      return limit;
    } catch (final NumberFormatException e) {
      throw new BadRequestException(ProtocolErrorCodes.PAGINATION_NUMBER_INVALID);
    }
  }
}
