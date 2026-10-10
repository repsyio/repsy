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
import io.repsy.protocols.pypi.protocol.PypiProtocolProvider;
import io.repsy.protocols.pypi.protocol.facades.PypiProtocolFacade;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@NullMarked
public abstract class AbstractPypiSimpleProtocolMethodHandler<ID>
    extends AbstractFacadeProtocolMethodHandler<PypiProtocolFacade<ID>> {

  private static final Pattern SIMPLE_PATTERN = Pattern.compile("^/simple(?:/([^/]+))?/?$");

  public AbstractPypiSimpleProtocolMethodHandler(
      final PathParser basePathParser,
      final PypiProtocolFacade<ID> pypiProtocolFacade,
      final PypiProtocolProvider provider) {
    super(
        HandlerRoute.read(HttpMethod.GET).method("simple").path(SIMPLE_PATTERN.asMatchPredicate()),
        basePathParser,
        pypiProtocolFacade,
        provider);
  }

  protected abstract @Nullable URI getNormalizedUri(
      HttpServletRequest request, @Nullable String packageName);

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws Exception {

    final var relativePath = ProtocolContextUtils.getRelativePath(context).getPath();

    final var matcher = SIMPLE_PATTERN.matcher(relativePath);

    if (!matcher.matches()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    final var packageName = matcher.group(1); // Can be null for package list

    final var normalizedUri = this.getNormalizedUri(request, packageName);

    if (normalizedUri != null) {
      return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT).location(normalizedUri).build();
    }

    final var resource = this.facade.getPackageList(context, packageName);

    return ResponseEntity.ok()
        .contentLength(resource.contentLength())
        .contentType(MediaType.TEXT_HTML)
        .body(resource);
  }
}
