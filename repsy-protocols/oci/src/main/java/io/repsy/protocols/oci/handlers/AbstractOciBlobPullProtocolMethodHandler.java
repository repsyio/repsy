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
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolProvider;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@code GET /v2/<repo>/<name>/blobs/<digest>}: the blob's bytes with the format's content type and
 * the digest. A missing blob is the format's exception (the OCI {@code BLOB_UNKNOWN} body).
 *
 * @param <F> the format's facade
 */
@NullMarked
public abstract class AbstractOciBlobPullProtocolMethodHandler<F>
    extends AbstractFacadeProtocolMethodHandler<F> {

  private final Pattern blobPattern;
  private final String contentType;

  /**
   * @param route the format's methods and properties; the path is {@code blobPattern}
   * @param blobPattern the format's blob path
   * @param contentType the {@code Content-Type} every blob is served with
   */
  protected AbstractOciBlobPullProtocolMethodHandler(
      final HandlerRoute route,
      final Pattern blobPattern,
      final String contentType,
      final PathParser basePathParser,
      final F facade,
      final ProtocolProvider provider) {

    super(route.path(blobPattern.asMatchPredicate()), basePathParser, facade, provider);
    this.blobPattern = blobPattern;
    this.contentType = contentType;
  }

  /** The blob's bytes; throws the format's not-found exception when the repo lacks it. */
  protected abstract Resource getBlob(
      ProtocolContext context, HttpServletRequest request, String digest) throws Exception;

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws Exception {

    final var matcher = OciPathMatches.match(this.blobPattern, context);

    if (matcher.isEmpty()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    final var digest = matcher.get().group(2);
    final var resource = this.getBlob(context, request, digest);

    return ResponseEntity.ok()
        .header(CONTENT_TYPE, this.contentType)
        .header(DOCKER_CONTENT_DIGEST, digest)
        .body(resource);
  }
}
