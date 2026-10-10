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

import static io.repsy.protocols.helm.shared.utils.HelmOciHttpValues.DOCKER_CONTENT_DIGEST;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.http.HttpHeaders.CONTENT_LENGTH;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Handles GET /v2/{repo}/{name}/manifests/{reference} — downloads a manifest. */
@NullMarked
public abstract class AbstractHelmOciManifestPullProtocolMethodHandler<ID>
    extends AbstractFacadeProtocolMethodHandler<HelmProtocolFacade<ID>> {

  private static final Pattern MANIFEST_PULL_PATTERN = Pattern.compile("^/([^/]+)/manifests/(.+)$");

  public AbstractHelmOciManifestPullProtocolMethodHandler(
      final PathParser basePathParser,
      final HelmProtocolFacade<ID> helmFacade,
      final HelmProtocolProvider provider) {
    super(
        HandlerRoute.of(Permission.READ, HttpMethod.GET)
            .writeOperation(false)
            .path(MANIFEST_PULL_PATTERN.asMatchPredicate()),
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
    final var matcher = MANIFEST_PULL_PATTERN.matcher(relativePath);

    if (!matcher.matches()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    final var name = matcher.group(1);
    final var reference = matcher.group(2);
    final var manifest = this.facade.getManifest(context, name, reference);
    final var contentLength = manifest.content().getBytes(UTF_8).length;

    return ResponseEntity.ok()
        .header(CONTENT_TYPE, manifest.mediaType())
        .header(CONTENT_LENGTH, String.valueOf(contentLength))
        .header(DOCKER_CONTENT_DIGEST, manifest.digest())
        .body(manifest.content());
  }
}
