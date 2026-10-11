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
import static org.springframework.http.HttpHeaders.CONTENT_LENGTH;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolProvider;
import io.repsy.protocols.oci.dtos.OciBlobInfo;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@code HEAD /v2/<repo>/<name>/blobs/<digest>}: 200 with the size, media type and digest of a
 * stored blob, or a bare 404.
 *
 * @param <F> what the format looks the blob up with
 */
public abstract class AbstractOciBlobCheckProtocolMethodHandler<F>
    extends AbstractFacadeProtocolMethodHandler<F> {

  private final Pattern blobPattern;

  /**
   * @param route the format's methods and properties; the path is {@code blobPattern}
   * @param blobPattern the format's blob path ({@link
   *     io.repsy.protocols.oci.utils.OciPathUtils#blob})
   */
  protected AbstractOciBlobCheckProtocolMethodHandler(
      final HandlerRoute route,
      final Pattern blobPattern,
      final PathParser basePathParser,
      final F facade,
      final ProtocolProvider provider) {

    super(route.path(blobPattern.asMatchPredicate()), basePathParser, facade, provider);
    this.blobPattern = blobPattern;
  }

  /** The stored blob of the repo with that digest. */
  protected abstract Optional<OciBlobInfo> findBlob(ProtocolContext context, String digest);

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    final var matcher = OciPathMatches.match(this.blobPattern, context);

    if (matcher.isEmpty()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    final var digest = matcher.get().group(2);
    final var blob = this.findBlob(context, digest);

    if (blob.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    return ResponseEntity.ok()
        .header(CONTENT_LENGTH, String.valueOf(blob.get().size()))
        .header(CONTENT_TYPE, blob.get().mediaType())
        .header(DOCKER_CONTENT_DIGEST, digest)
        .build();
  }
}
