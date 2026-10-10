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

import static org.springframework.http.MediaType.APPLICATION_JSON;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.nuget.protocol.NuGetProtocolProvider;
import io.repsy.protocols.nuget.protocol.facades.contracts.NuGetProtocolFacade;
import io.repsy.protocols.nuget.shared.utils.NuGetBaseUrlResolver;
import io.repsy.protocols.nuget.shared.utils.NuGetPackageUtils;
import io.repsy.protocols.nuget.shared.utils.NuGetVersionUtils;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

@Slf4j
@NullMarked
public abstract class AbstractNuGetSearchProtocolMethodHandler
    extends AbstractFacadeProtocolMethodHandler<NuGetProtocolFacade> {

  private final NuGetBaseUrlResolver baseUrlResolver;

  public AbstractNuGetSearchProtocolMethodHandler(
      final PathParser basePathParser,
      final NuGetProtocolFacade facade,
      final NuGetProtocolProvider provider,
      final NuGetBaseUrlResolver baseUrlResolver) {
    super(HandlerRoute.read(HttpMethod.GET), basePathParser, facade, provider);
    this.baseUrlResolver = baseUrlResolver;
  }

  @Override
  protected boolean accepts(final HttpMethod method, final HttpServletRequest request) {
    return super.accepts(method, request) && request.getServletPath().contains("/v3/search");
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    try {
      final var q = request.getParameter("q");
      final var skipStr = request.getParameter("skip");
      final var takeStr = request.getParameter("take");
      final var prerelease = "true".equalsIgnoreCase(request.getParameter("prerelease"));
      final var semVer2 = NuGetVersionUtils.acceptsSemVer2(request.getParameter("semVerLevel"));

      final var skip = NuGetPackageUtils.parseNonNegativeParam(skipStr, 0, "skip");
      final var take =
          Math.min(
              NuGetPackageUtils.parseNonNegativeParam(takeStr, 20, "take"),
              NuGetPackageUtils.MAX_SEARCH_TAKE);

      final var repoName = ProtocolContextUtils.<Object>getRepoInfo(context).getName();
      final var baseUrl = this.baseUrlResolver.baseUrl(request, repoName);

      final var results =
          this.facade.search(context, q != null ? q : "", skip, take, prerelease, semVer2, baseUrl);

      return ResponseEntity.ok().contentType(APPLICATION_JSON).body(results);
    } catch (final IllegalArgumentException e) {
      log.debug("NuGet search: invalid paging parameter: {}", e.getMessage());
      return ResponseEntity.badRequest().build();
    }
  }
}
