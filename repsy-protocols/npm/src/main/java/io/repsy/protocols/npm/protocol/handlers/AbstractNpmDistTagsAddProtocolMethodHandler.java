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

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.npm.protocol.facades.NpmProtocolFacade;
import io.repsy.protocols.npm.shared.utils.ExtractPath;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * {@code PUT /{repo}/-/package/{package}/dist-tags/{tag}}, which {@code npm dist-tag add} calls. It
 * answers the JSON of {@code NpmDistTagsResponse}, because yarn classic takes an answer without an
 * {@code ok} field for a failure (RPS-1362).
 */
@NullMarked
public abstract class AbstractNpmDistTagsAddProtocolMethodHandler
    extends AbstractFacadeProtocolMethodHandler<NpmProtocolFacade> {

  private static final Pattern DIST_TAGS_ADD_PATTERN =
      Pattern.compile("^/-/package/(.+)/dist-tags/([^/]+)$");

  public AbstractNpmDistTagsAddProtocolMethodHandler(
      @Qualifier("osNpmPathParser") final PathParser basePathParser,
      final NpmProtocolFacade npmProtocolFacade,
      final NpmProtocolProvider provider) {
    super(
        HandlerRoute.write(HttpMethod.PUT).path(DIST_TAGS_ADD_PATTERN.asMatchPredicate()),
        basePathParser,
        npmProtocolFacade,
        provider);
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws Exception {

    final var relativePath = ProtocolContextUtils.getRelativePath(context).getPath();
    final var matcher = DIST_TAGS_ADD_PATTERN.matcher(relativePath);

    if (!matcher.matches()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Parser validation failed");
    }

    final var packagePath = matcher.group(1);
    final var tagName = matcher.group(2);

    final var pathVars = ExtractPath.extractPathVars(packagePath);
    final var versionName = this.readRequestBody(request);

    this.facade.addDistributionTag(
        context, pathVars.scopeName(), pathVars.packageName(), tagName, versionName);

    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            NpmDistTagsResponse.of(
                pathVars.scopeName(),
                pathVars.packageName(),
                this.facade.getMappedDistributionTags(
                    context, pathVars.scopeName(), pathVars.packageName())));
  }

  private String readRequestBody(final HttpServletRequest request) throws Exception {
    final var reader = request.getReader();
    final var stringBuilder = new StringBuilder();
    String line;

    while ((line = reader.readLine()) != null) {
      stringBuilder.append(line);
    }

    return stringBuilder.toString();
  }
}
