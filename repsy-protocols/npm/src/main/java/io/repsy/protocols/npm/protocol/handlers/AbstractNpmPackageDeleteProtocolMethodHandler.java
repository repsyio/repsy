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
package io.repsy.protocols.npm.protocol.handlers;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.npm.protocol.facades.NpmProtocolFacade;
import io.repsy.protocols.npm.shared.utils.ExtractPath;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@NullMarked
public abstract class AbstractNpmPackageDeleteProtocolMethodHandler
    implements ProtocolMethodHandler {

  private static final Pattern DELETE_PATTERN = Pattern.compile("^/(.+?)/-rev/.*");

  private final PathParser basePathParser;
  private final NpmProtocolFacade npmProtocolFacade;

  public AbstractNpmPackageDeleteProtocolMethodHandler(
      @Qualifier("npmPathParser") final PathParser basePathParser,
      final NpmProtocolFacade npmProtocolFacade,
      final NpmProtocolProvider provider) {
    this.basePathParser = basePathParser;
    this.npmProtocolFacade = npmProtocolFacade;

    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.DELETE);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of("permission", Permission.WRITE, "writeOperation", true);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      if (!HttpMethod.DELETE.equals(HttpMethod.valueOf(request.getMethod()))) {
        return Optional.empty();
      }

      final var parsedPathOpt = this.basePathParser.parse(request);
      if (parsedPathOpt.isEmpty()) {
        return Optional.empty();
      }

      final var relativePath = ProtocolContextUtils.getRelativePath(parsedPathOpt.get()).getPath();

      if (!DELETE_PATTERN.matcher(relativePath).matches()
          || relativePath.contains("dist-tags")) { // Not dist-tags delete
        return Optional.empty();
      }

      return parsedPathOpt;
    };
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext protocolContext,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws Exception {

    final var relativePath = ProtocolContextUtils.getRelativePath(protocolContext).getPath();
    final var matcher = DELETE_PATTERN.matcher(relativePath);

    if (!matcher.matches()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    final var packagePath = matcher.group(1);

    final var pathVars = ExtractPath.extractPathVars(packagePath);

    this.npmProtocolFacade.deletePackage(
        protocolContext, pathVars.scopeName(), pathVars.packageName());

    return ResponseEntity.ok().build();
  }
}
