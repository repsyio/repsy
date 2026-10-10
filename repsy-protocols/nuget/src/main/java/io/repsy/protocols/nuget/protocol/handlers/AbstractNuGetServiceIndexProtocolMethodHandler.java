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
package io.repsy.protocols.nuget.protocol.handlers;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.nuget.protocol.NuGetProtocolProvider;
import io.repsy.protocols.nuget.protocol.facades.contracts.NuGetProtocolFacade;
import io.repsy.protocols.nuget.shared.utils.NuGetBaseUrlResolver;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@NullMarked
public abstract class AbstractNuGetServiceIndexProtocolMethodHandler
    extends AbstractFacadeProtocolMethodHandler<NuGetProtocolFacade> {

  private final NuGetBaseUrlResolver baseUrlResolver;

  public AbstractNuGetServiceIndexProtocolMethodHandler(
      final PathParser basePathParser,
      final NuGetProtocolFacade facade,
      final NuGetProtocolProvider provider,
      final NuGetBaseUrlResolver baseUrlResolver) {
    super(
        HandlerRoute.read(HttpMethod.GET)
            .skipPreProcessor(true)
            .skipHeaderPreProcessor(true)
            .head(
                HandlerRoute.read(HttpMethod.HEAD)
                    .skipUsagePostProcessor(true)
                    .skipPreProcessor(true)),
        basePathParser,
        facade,
        provider);
    this.baseUrlResolver = baseUrlResolver;
  }

  @Override
  protected boolean accepts(final HttpMethod method, final HttpServletRequest request) {
    return super.accepts(method, request)
        && request.getRequestURI().toLowerCase(Locale.ROOT).endsWith("/v3/index.json");
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    final var repoName = ProtocolContextUtils.<Object>getRepoInfo(context).getName();
    final var baseUrl = this.baseUrlResolver.baseUrl(request, repoName);
    final var serviceIndex = this.facade.getServiceIndex(context, baseUrl);

    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(serviceIndex);
  }

  /** The headers of the service index; it is a fixed document, so nothing is built. */
  @Override
  public ResponseEntity<Object> handleHead(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).build();
  }
}
