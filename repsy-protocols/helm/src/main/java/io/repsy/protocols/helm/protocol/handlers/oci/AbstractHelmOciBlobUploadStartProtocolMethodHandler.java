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

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.helm.protocol.facades.HelmProtocolFacade;
import io.repsy.protocols.oci.handlers.AbstractOciUploadStartProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.http.PublicUrls;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;

/** Handles POST /v2/{repo}/{name}/blobs/uploads/ — starts a blob upload session. */
@NullMarked
public abstract class AbstractHelmOciBlobUploadStartProtocolMethodHandler<ID>
    extends AbstractOciUploadStartProtocolMethodHandler {

  protected final HelmProtocolFacade<ID> facade;

  public AbstractHelmOciBlobUploadStartProtocolMethodHandler(
      final PathParser basePathParser,
      final HelmProtocolFacade<ID> helmFacade,
      final HelmProtocolProvider provider) {
    super(
        HandlerRoute.of(Permission.WRITE, HttpMethod.POST)
            .skipHeaderPreProcessor(true)
            .writeOperation(true),
        basePathParser,
        provider);
    this.facade = helmFacade;
  }

  @Override
  protected UUID startUpload(final ProtocolContext context, final HttpServletRequest request) {
    return this.facade.startBlobUpload(context);
  }

  @Override
  protected String uploadLocation(final HttpServletRequest request, final UUID uploadId) {
    final var requestPath = stripTrailingSlashes(request.getRequestURI());

    return PublicUrls.currentContextRoot() + requestPath + "/" + uploadId;
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
