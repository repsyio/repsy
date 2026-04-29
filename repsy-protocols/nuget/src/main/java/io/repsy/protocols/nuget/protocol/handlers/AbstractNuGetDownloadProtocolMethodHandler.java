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
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.nuget.protocol.NuGetProtocolProvider;
import io.repsy.protocols.nuget.protocol.facades.contract.NuGetProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Slf4j
@NullMarked
public abstract class AbstractNuGetDownloadProtocolMethodHandler implements ProtocolMethodHandler {

  private static final Pattern NUPKG_PATTERN =
      Pattern.compile(".*/v3/package/.+/.+/.+\\.nupkg$");
  private static final Pattern NUSPEC_PATTERN =
      Pattern.compile(".*/v3/package/.+/.+/.+\\.nuspec$");

  private final PathParser basePathParser;
  private final NuGetProtocolFacade facade;
  private final boolean isNupkg;

  protected AbstractNuGetDownloadProtocolMethodHandler(
      final PathParser basePathParser,
      final NuGetProtocolFacade facade,
      final NuGetProtocolProvider provider,
      final boolean isNupkg) {

    this.basePathParser = basePathParser;
    this.facade = facade;
    this.isNupkg = isNupkg;

    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.GET);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of(
        "permission", Permission.READ,
        "writeOperation", false);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      if (!HttpMethod.GET.name().equals(request.getMethod())) {
        return Optional.empty();
      }

      final var path = request.getServletPath();
      final var pattern = isNupkg ? NUPKG_PATTERN : NUSPEC_PATTERN;

      if (!pattern.matcher(path).matches()) {
        return Optional.empty();
      }

      return this.basePathParser.parse(request);
    };
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    try {
      final var resource = isNupkg ? facade.downloadNupkg(context) : facade.downloadNuspec(context);
      final var contentType = isNupkg ? MediaType.APPLICATION_OCTET_STREAM : MediaType.APPLICATION_XML;

      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_TYPE, contentType.toString())
          .body(resource);
    } catch (final Exception e) {
      log.debug("NuGet download failed: {}", e.getMessage());
      return ResponseEntity.notFound().build();
    }
  }
}
