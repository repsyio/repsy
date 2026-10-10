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
package io.repsy.protocols.helm.protocol.handlers.classic;

import static org.springframework.http.HttpHeaders.CONTENT_DISPOSITION;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.helm.protocol.facades.HelmProtocolFacade;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.http.ResourceResponses;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Handles GET /{repo}/charts/{filename} — streams a .tgz chart archive. */
@NullMarked
public abstract class AbstractHelmChartPullProtocolMethodHandler<ID>
    extends AbstractFacadeProtocolMethodHandler<HelmProtocolFacade<ID>> {

  private static final Pattern CHART_PULL_PATTERN = Pattern.compile("^/charts/([^/]+\\.tgz)$");

  public AbstractHelmChartPullProtocolMethodHandler(
      final PathParser basePathParser,
      final HelmProtocolFacade<ID> helmFacade,
      final HelmProtocolProvider provider) {
    super(
        HandlerRoute.of(Permission.READ, HttpMethod.GET)
            .writeOperation(false)
            .path(CHART_PULL_PATTERN.asMatchPredicate()),
        basePathParser,
        helmFacade,
        provider);
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws Exception {

    final var relativePath = ProtocolContextUtils.getRelativePath(context).getPath();
    final var matcher = CHART_PULL_PATTERN.matcher(relativePath);

    if (!matcher.matches()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    final var filename = matcher.group(1);
    final var resource = this.facade.getChart(context, filename);

    return ResponseEntity.ok()
        .header(CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
        .header(CONTENT_DISPOSITION, ResourceResponses.attachment(filename))
        .body(resource);
  }
}
