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
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.libs.protocol.router.ProtocolProvider;
import io.repsy.protocols.pypi.protocol.facades.PypiProtocolFacade;
import io.repsy.protocols.pypi.shared.utils.PackageUtils;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@NullMarked
public abstract class AbstractPypiPackageUploadProtocolMethodHandler<ID>
    implements ProtocolMethodHandler {

  // Upload endpoint pattern: POST /{owner}/{repo} (root level)
  private static final Pattern UPLOAD_PATTERN = Pattern.compile("^/?$");

  private final PathParser basePathParser;
  private final PypiProtocolFacade<ID> pypiProtocolFacade;

  public AbstractPypiPackageUploadProtocolMethodHandler(
      final PathParser basePathParser,
      final PypiProtocolFacade<ID> pypiProtocolFacade,
      final ProtocolProvider provider) {

    provider.registerMethodHandler(this);

    this.basePathParser = basePathParser;
    this.pypiProtocolFacade = pypiProtocolFacade;
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.POST);
  }

  @Override
  public Map<String, Object> getProperties() {

    return Map.of("permission", Permission.WRITE, "writeOperation", true);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      if (!HttpMethod.POST.equals(HttpMethod.valueOf(request.getMethod()))) {
        return Optional.empty();
      }

      if (!(request instanceof MultipartHttpServletRequest)) {
        return Optional.empty();
      }

      final var contextOpt = this.basePathParser.parse(request);
      if (contextOpt.isEmpty()) {
        return Optional.empty();
      }

      final var relativePath = ProtocolContextUtils.getRelativePath(contextOpt.get());

      if (!UPLOAD_PATTERN.matcher(relativePath.getPath()).matches()) {
        return Optional.empty();
      }

      return contextOpt;
    };
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

    this.pypiProtocolFacade.uploadPackage(
        context, PackageUtils.parseMultipartUploadRequestParameters(multipartRequest), file);

    return ResponseEntity.ok().build();
  }
}
