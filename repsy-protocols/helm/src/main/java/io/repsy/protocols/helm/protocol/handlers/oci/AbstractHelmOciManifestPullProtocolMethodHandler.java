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
import io.repsy.protocols.oci.dtos.OciManifestInfo;
import io.repsy.protocols.oci.handlers.AbstractOciManifestPullProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;

/**
 * Handles GET /v2/{repo}/{name}/manifests/{reference} — downloads a manifest. The {@code Accept}
 * header is not negotiated.
 */
@NullMarked
public abstract class AbstractHelmOciManifestPullProtocolMethodHandler<ID>
    extends AbstractOciManifestPullProtocolMethodHandler<HelmProtocolFacade<ID>> {

  public AbstractHelmOciManifestPullProtocolMethodHandler(
      final PathParser basePathParser,
      final HelmProtocolFacade<ID> helmFacade,
      final HelmProtocolProvider provider) {
    super(
        HandlerRoute.of(Permission.READ, HttpMethod.GET).writeOperation(false),
        basePathParser,
        helmFacade,
        provider);
  }

  @Override
  protected OciManifestInfo getManifest(
      final ProtocolContext context,
      final HttpServletRequest request,
      final String name,
      final String reference)
      throws IOException {

    final var manifest = this.facade.getManifest(context, name, reference);

    return new OciManifestInfo(manifest.mediaType(), manifest.content(), manifest.digest());
  }
}
