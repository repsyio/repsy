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

import static org.springframework.http.HttpHeaders.CONTENT_DISPOSITION;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_OCTET_STREAM;
import static org.springframework.http.MediaType.APPLICATION_XML;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.nuget.protocol.NuGetProtocolProvider;
import io.repsy.protocols.nuget.protocol.facades.contracts.NuGetProtocolFacade;
import io.repsy.protocols.nuget.shared.utils.NuGetBaseUrlResolver;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Answers a {@code HEAD} on the read routes of a NuGet feed like the {@code GET} of the same path
 * would, without the body (RPS-1465): the {@code .nupkg} and {@code .nuspec} downloads, the
 * flat-container version list and the registration index and leaf. Before, all of them were
 * answered by the router's generic {@code unknownPath} 404, which on a {@code .nupkg} URL even
 * carried Spring's {@code Content-Disposition: inline;filename=f.txt}.
 *
 * <p>The answer is the status and the headers of the {@code GET}, and the {@code Content-Length} of
 * the two files. The JSON routes send no {@code Content-Length}: the body is built per request and
 * a {@code HEAD} does not serialize it. The authorization is the one of the {@code GET}: read
 * permission, decided by the same pre-processor. A {@code HEAD} is not a download, so it does not
 * count towards the package's downloads. The service index has a handler of its own ({@link
 * AbstractNuGetServiceIndexHeadProtocolMethodHandler}) because it needs no credentials, and search
 * and autocomplete are queries that nothing asks a {@code HEAD} about.
 */
@Slf4j
@NullMarked
public abstract class AbstractNuGetHeadProtocolMethodHandler implements ProtocolMethodHandler {

  private final PathParser basePathParser;
  private final NuGetProtocolFacade facade;
  private final NuGetBaseUrlResolver baseUrlResolver;

  protected AbstractNuGetHeadProtocolMethodHandler(
      final PathParser basePathParser,
      final NuGetProtocolFacade facade,
      final NuGetProtocolProvider provider,
      final NuGetBaseUrlResolver baseUrlResolver) {

    this.basePathParser = basePathParser;
    this.facade = facade;
    this.baseUrlResolver = baseUrlResolver;

    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.HEAD);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of(
        "permission", Permission.READ, "writeOperation", false, "skipUsagePostProcessor", true);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      final var path = request.getServletPath();

      if (!isRoute(path)) {
        return Optional.empty();
      }

      return this.basePathParser.parse(request);
    };
  }

  private static boolean isRoute(final String path) {
    return AbstractNuGetDownloadProtocolMethodHandler.NUPKG_PATTERN.matcher(path).matches()
        || AbstractNuGetDownloadProtocolMethodHandler.NUSPEC_PATTERN.matcher(path).matches()
        || AbstractNuGetPackageVersionsProtocolMethodHandler.VERSIONS_PATTERN
            .matcher(path)
            .matches()
        || AbstractNuGetRegistrationProtocolMethodHandler.INDEX_PATTERN.matcher(path).matches()
        || AbstractNuGetRegistrationProtocolMethodHandler.LEAF_PATTERN.matcher(path).matches();
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws IOException {

    final var path = request.getServletPath();

    try {
      if (AbstractNuGetDownloadProtocolMethodHandler.NUPKG_PATTERN.matcher(path).matches()) {
        final var resource = this.facade.getNuPackage(context);

        return ResponseEntity.ok()
            .header(CONTENT_TYPE, APPLICATION_OCTET_STREAM.toString())
            .header(
                CONTENT_DISPOSITION,
                AbstractNuGetDownloadProtocolMethodHandler.contentDisposition(context, true))
            .contentLength(resource.contentLength())
            .build();
      }

      if (AbstractNuGetDownloadProtocolMethodHandler.NUSPEC_PATTERN.matcher(path).matches()) {
        final var resource = this.facade.downloadNuspec(context);

        return ResponseEntity.ok()
            .header(CONTENT_TYPE, APPLICATION_XML.toString())
            .header(
                CONTENT_DISPOSITION,
                AbstractNuGetDownloadProtocolMethodHandler.contentDisposition(context, false))
            .contentLength(resource.contentLength())
            .build();
      }

      this.checkJsonRoute(context, request, path);

      return ResponseEntity.ok().contentType(APPLICATION_JSON).build();
    } catch (final ItemNotFoundException | IllegalArgumentException e) {
      log.debug("NuGet HEAD not found: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
  }

  /** Resolves what the {@code GET} of a JSON route would answer, so that a missing one is a 404. */
  private void checkJsonRoute(
      final ProtocolContext context, final HttpServletRequest request, final String path) {

    if (AbstractNuGetPackageVersionsProtocolMethodHandler.VERSIONS_PATTERN
        .matcher(path)
        .matches()) {
      this.facade.getPackageVersions(context);
      return;
    }

    final var repoName = ProtocolContextUtils.<Object>getRepoInfo(context).getName();
    final var baseUrl = this.baseUrlResolver.baseUrl(request, repoName);

    if (AbstractNuGetRegistrationProtocolMethodHandler.INDEX_PATTERN.matcher(path).matches()) {
      this.facade.getRegistrationIndex(context, baseUrl);
    } else {
      this.facade.getRegistrationLeaf(context, baseUrl);
    }
  }
}
