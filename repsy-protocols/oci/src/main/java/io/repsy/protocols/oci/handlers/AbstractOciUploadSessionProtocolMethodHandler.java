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

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolProvider;
import io.repsy.protocols.oci.utils.OciPathUtils;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.jspecify.annotations.NullMarked;

/**
 * A route on one upload session, {@code /v2/<repo>/<name>/blobs/uploads/<id>}: what the chunk,
 * status and finalize handlers ask the format about the session.
 *
 * @param <F> the format's facade
 */
@NullMarked
public abstract class AbstractOciUploadSessionProtocolMethodHandler<F>
    extends AbstractFacadeProtocolMethodHandler<F> {

  /**
   * @param route the format's methods and properties; the path is {@link
   *     OciPathUtils#UPLOAD_SESSION}
   */
  protected AbstractOciUploadSessionProtocolMethodHandler(
      final HandlerRoute route,
      final PathParser basePathParser,
      final F facade,
      final ProtocolProvider provider) {

    super(
        route.path(OciPathUtils.UPLOAD_SESSION.asMatchPredicate()),
        basePathParser,
        facade,
        provider);
  }

  /**
   * The session id as the {@code Docker-Upload-UUID} header reports it, called before anything else
   * is done with the request, so a format that parses the id fails there. The id as the path
   * spelled it unless the format overrides this.
   */
  protected String reportedUploadId(final String uploadId) {
    return uploadId;
  }

  /**
   * The bytes the session holds; throws the format's not-found exception when there is no such
   * session.
   */
  protected abstract long uploadSize(ProtocolContext context, String name, String uploadId)
      throws IOException;

  /** The {@code Location} of the session, for its next chunk or its finalize. */
  protected abstract String uploadLocation(
      ProtocolContext context, HttpServletRequest request, String name, String uploadId);
}
