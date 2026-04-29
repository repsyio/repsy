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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Slf4j
@NullMarked
public abstract class AbstractNuGetRegistrationProtocolMethodHandler implements ProtocolMethodHandler {

  private static final Pattern INDEX_PATTERN = Pattern.compile(".*/v3/registration/.+/index\\.json$");
  private static final Pattern LEAF_PATTERN = Pattern.compile(".*/v3/registration/.+/.+\\.json$");

  private final PathParser basePathParser;
  private final NuGetProtocolFacade facade;
  private final boolean isIndex;

  protected AbstractNuGetRegistrationProtocolMethodHandler(
      final PathParser basePathParser,
      final NuGetProtocolFacade facade,
      final NuGetProtocolProvider provider,
      final boolean isIndex) {

    this.basePathParser = basePathParser;
    this.facade = facade;
    this.isIndex = isIndex;

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
      final var pattern = isIndex ? INDEX_PATTERN : LEAF_PATTERN;

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

    if (isIndex) {
      return handleRegistrationIndex(context, request);
    } else {
      return handleRegistrationLeaf(context, request);
    }
  }

  private ResponseEntity<Object> handleRegistrationIndex(
      final ProtocolContext context, final HttpServletRequest request) {
    // Return empty registration index for now
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of(
            "count", 0,
            "items", List.of()));
  }

  private ResponseEntity<Object> handleRegistrationLeaf(
      final ProtocolContext context, final HttpServletRequest request) {
    // Return not found for unimplemented registration leaf
    return ResponseEntity.notFound().build();
  }
}
