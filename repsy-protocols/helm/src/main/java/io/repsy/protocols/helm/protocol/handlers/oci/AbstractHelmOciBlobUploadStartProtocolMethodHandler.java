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
package io.repsy.protocols.helm.protocol.handlers.oci;

import static io.repsy.protocols.helm.shared.utils.HelmOciHttpValues.DOCKER_UPLOAD_UUID;
import static org.springframework.http.HttpHeaders.LOCATION;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.helm.protocol.facades.HelmProtocolFacade;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Handles POST /v2/{repo}/{name}/blobs/uploads/ — starts a blob upload session. */
@NullMarked
public abstract class AbstractHelmOciBlobUploadStartProtocolMethodHandler<ID>
    extends AbstractFacadeProtocolMethodHandler<HelmProtocolFacade<ID>> {

  private static final Pattern UPLOAD_START_PATTERN = Pattern.compile("^/([^/]+)/blobs/uploads/?$");

  public AbstractHelmOciBlobUploadStartProtocolMethodHandler(
      final PathParser basePathParser,
      final HelmProtocolFacade<ID> helmFacade,
      final HelmProtocolProvider provider) {
    super(
        HandlerRoute.of(Permission.WRITE, HttpMethod.POST)
            .skipHeaderPreProcessor(true)
            .writeOperation(true)
            .path(UPLOAD_START_PATTERN.asMatchPredicate()),
        basePathParser,
        helmFacade,
        provider);
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    final var relativePath = ProtocolContextUtils.getRelativePath(context).getPath();
    final var matcher = UPLOAD_START_PATTERN.matcher(relativePath);

    if (!matcher.matches()) {
      return ResponseEntity.internalServerError().build();
    }

    final var uploadId = this.facade.startBlobUpload(context);

    final var requestPath = stripTrailingSlashes(request.getRequestURI());
    final var location =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path(requestPath + "/" + uploadId)
            .build()
            .toUriString();

    return ResponseEntity.accepted()
        .header(LOCATION, location)
        .header(DOCKER_UPLOAD_UUID, uploadId.toString())
        .build();
  }

  /** Drops the slashes at the end of the path, a loop instead of a regex so it stays linear. */
  private static String stripTrailingSlashes(final String path) {
    var end = path.length();

    while (end > 0 && path.charAt(end - 1) == '/') {
      end--;
    }

    return path.substring(0, end);
  }
}
