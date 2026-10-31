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

import static io.repsy.protocols.oci.utils.OciMediaTypeUtils.OCI_MANIFEST_SCHEMA1;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.helm.protocol.facades.HelmProtocolFacade;
import io.repsy.protocols.oci.dtos.OciManifestInfo;
import io.repsy.protocols.oci.handlers.AbstractOciManifestPullProtocolMethodHandler;
import io.repsy.protocols.oci.utils.OciAcceptHeaderUtils;
import io.repsy.protocols.oci.utils.OciMediaTypeUtils;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.HttpMediaTypeNotAcceptableException;

/**
 * Handles GET /v2/{repo}/{name}/manifests/{reference} — downloads a manifest. With {@code
 * repsy.helm.oci.strict-wire} on, the {@code Accept} header is negotiated as the Docker registry
 * does (RPS-2164): a header that names no manifest type this registry serves is a 406 before the
 * manifest is read.
 */
public abstract class AbstractHelmOciManifestPullProtocolMethodHandler<I>
    extends AbstractOciManifestPullProtocolMethodHandler<HelmProtocolFacade<I>> {

  private static final List<String> DEFAULT_ACCEPT_TYPES = List.of(OCI_MANIFEST_SCHEMA1);

  private final boolean strictWire;

  protected AbstractHelmOciManifestPullProtocolMethodHandler(
      final PathParser basePathParser,
      final HelmProtocolFacade<I> helmFacade,
      final HelmProtocolProvider provider,
      final boolean strictWire) {
    super(
        HandlerRoute.of(Permission.READ, HttpMethod.GET).writeOperation(false),
        basePathParser,
        helmFacade,
        provider);
    this.strictWire = strictWire;
  }

  @Override
  protected void checkAcceptable(final HttpServletRequest request)
      throws HttpMediaTypeNotAcceptableException {

    if (!this.strictWire) {
      return;
    }

    final var acceptHeaders =
        OciAcceptHeaderUtils.parse(request.getHeader(HttpHeaders.ACCEPT), DEFAULT_ACCEPT_TYPES);

    if (OciMediaTypeUtils.getPreferredMediaType(acceptHeaders) == null) {
      throw new HttpMediaTypeNotAcceptableException(
          "The Accept header names no manifest media type this repository serves.");
    }
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
