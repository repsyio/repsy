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

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
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
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Slf4j
@NullMarked
public abstract class AbstractNuGetRegistrationProtocolMethodHandler
    extends AbstractFacadeProtocolMethodHandler<NuGetProtocolFacade> {

  static final Pattern INDEX_PATTERN =
      Pattern.compile(".*/v3/registration/[^/]+/index\\.json$", Pattern.CASE_INSENSITIVE);

  /** Matches leaf URLs like /v3/registration/{id}/{version}.json — excludes index.json. */
  static final Pattern LEAF_PATTERN =
      Pattern.compile(
          ".*/v3/registration/[^/]+/(?!index\\.json$)[^/]+\\.json$", Pattern.CASE_INSENSITIVE);

  private final NuGetBaseUrlResolver baseUrlResolver;
  private final boolean index;

  protected AbstractNuGetRegistrationProtocolMethodHandler(
      final PathParser basePathParser,
      final NuGetProtocolFacade facade,
      final NuGetProtocolProvider provider,
      final NuGetBaseUrlResolver baseUrlResolver,
      final boolean index) {
    super(HandlerRoute.read(HttpMethod.GET).head(), basePathParser, facade, provider);
    this.baseUrlResolver = baseUrlResolver;
    this.index = index;
  }

  @Override
  protected boolean accepts(final HttpMethod method, final HttpServletRequest request) {
    return super.accepts(method, request)
        && (this.index ? INDEX_PATTERN : LEAF_PATTERN).matcher(request.getServletPath()).matches();
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    try {
      final var repoName = ProtocolContextUtils.<Object>getRepoInfo(context).getName();
      final var baseUrl = this.baseUrlResolver.baseUrl(request, repoName);

      if (this.index) {
        final var result = this.facade.getRegistrationIndex(context, baseUrl);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result);
      }

      final var result = this.facade.getRegistrationLeaf(context, baseUrl);
      return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result);

    } catch (final ItemNotFoundException | IllegalArgumentException e) {
      log.debug("NuGet registration not found: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    } catch (final Exception e) {
      log.error("NuGet registration failed: ", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /** Resolves what the {@code GET} would answer, so that a missing package is a 404. */
  @Override
  public ResponseEntity<Object> handleHead(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {
    try {
      final var repoName = ProtocolContextUtils.<Object>getRepoInfo(context).getName();
      final var baseUrl = this.baseUrlResolver.baseUrl(request, repoName);

      if (this.index) {
        this.facade.getRegistrationIndex(context, baseUrl);
      } else {
        this.facade.getRegistrationLeaf(context, baseUrl);
      }

      return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).build();
    } catch (final ItemNotFoundException | IllegalArgumentException e) {
      log.debug("NuGet HEAD not found: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
  }
}
