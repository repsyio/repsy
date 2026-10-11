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
package io.repsy.protocols.oci.handlers;

import static io.repsy.protocols.oci.constants.OciConstants.DOCKER_CONTENT_DIGEST;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.http.HttpHeaders.CONTENT_LENGTH;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolProvider;
import io.repsy.protocols.oci.dtos.OciManifestInfo;
import io.repsy.protocols.oci.utils.OciPathUtils;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@code HEAD /v2/<repo>/<name>/manifests/<reference>}: the headers a {@code GET} of the manifest
 * answers, without the body. A format answers a missing manifest either with a bare 404 (an empty
 * {@link #findManifest}) or with its not-found exception (the OCI error body).
 *
 * @param <F> the format's facade
 */
public abstract class AbstractOciManifestCheckProtocolMethodHandler<F>
    extends AbstractFacadeProtocolMethodHandler<F> {

  protected AbstractOciManifestCheckProtocolMethodHandler(
      final HandlerRoute route,
      final PathParser basePathParser,
      final F facade,
      final ProtocolProvider provider) {

    super(route.path(OciPathUtils.MANIFEST.asMatchPredicate()), basePathParser, facade, provider);
  }

  /** The manifest the reference (tag or digest) names. */
  protected abstract Optional<OciManifestInfo> findManifest(
      ProtocolContext context, HttpServletRequest request, String name, String reference)
      throws Exception;

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws Exception {

    final var matcher = OciPathMatches.match(OciPathUtils.MANIFEST, context);

    if (matcher.isEmpty()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    final var manifest =
        this.findManifest(context, request, matcher.get().group(1), matcher.get().group(2));

    if (manifest.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    return ResponseEntity.ok()
        .header(CONTENT_TYPE, manifest.get().mediaType())
        .header(CONTENT_LENGTH, String.valueOf(manifest.get().content().getBytes(UTF_8).length))
        .header(DOCKER_CONTENT_DIGEST, manifest.get().digest())
        .build();
  }
}
