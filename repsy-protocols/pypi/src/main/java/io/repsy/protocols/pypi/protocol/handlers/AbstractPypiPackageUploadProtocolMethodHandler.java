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
package io.repsy.protocols.pypi.protocol.handlers;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolProvider;
import io.repsy.protocols.pypi.protocol.facades.PypiProtocolFacade;
import io.repsy.protocols.pypi.shared.utils.PypiPackageUtils;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartHttpServletRequest;

public abstract class AbstractPypiPackageUploadProtocolMethodHandler<ID>
    extends AbstractFacadeProtocolMethodHandler<PypiProtocolFacade<ID>> {

  // Upload endpoint pattern: POST /{owner}/{repo} (root level)
  private static final Pattern UPLOAD_PATTERN = Pattern.compile("^/?$");

  public AbstractPypiPackageUploadProtocolMethodHandler(
      final PathParser basePathParser,
      final PypiProtocolFacade<ID> pypiProtocolFacade,
      final ProtocolProvider provider) {
    super(
        HandlerRoute.write(HttpMethod.POST).path(UPLOAD_PATTERN.asMatchPredicate()),
        basePathParser,
        pypiProtocolFacade,
        provider);
  }

  @Override
  protected boolean accepts(final HttpMethod method, final HttpServletRequest request) {
    return super.accepts(method, request) && request instanceof MultipartHttpServletRequest;
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws Exception {

    if (!(request instanceof final MultipartHttpServletRequest multipartRequest)) {
      return ResponseEntity.badRequest().build();
    }

    final var file = multipartRequest.getFile("content");
    if (file == null) {
      return ResponseEntity.badRequest().build();
    }

    this.facade.uploadPackage(
        context, PypiPackageUtils.parseMultipartUploadRequestParameters(multipartRequest), file);

    return ResponseEntity.ok().build();
  }
}
