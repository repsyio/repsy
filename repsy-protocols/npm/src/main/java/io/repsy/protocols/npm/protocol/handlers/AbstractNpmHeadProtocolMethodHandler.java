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
import io.repsy.libs.protocol.router.ProtocolProvider;
import io.repsy.protocols.npm.protocol.facades.NpmProtocolFacade;
import io.repsy.protocols.npm.shared.utils.ExtractPath;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Answers a {@code HEAD} like the {@code GET} of the same path would, without the body: 200 for a
 * package or a tarball the repo has, 404 for one it does not (RPS-1358). What is not a package or a
 * tarball, that is the repo itself and the registry endpoints below {@code /-/}, is answered 200 as
 * before.
 */
@NullMarked
public abstract class AbstractNpmHeadProtocolMethodHandler implements ProtocolMethodHandler {

  private static final Pattern DOWNLOAD_PATTERN = Pattern.compile("^/(.+?)/-/(.+)");
  private static final Pattern PACKAGE_PATTERN = Pattern.compile("^/(.+?)$");

  private final PathParser pathParser;
  private final NpmProtocolFacade npmProtocolFacade;

  public AbstractNpmHeadProtocolMethodHandler(
      final PathParser basePathParser,
      final NpmProtocolFacade npmProtocolFacade,
      final ProtocolProvider provider) {

    provider.registerMethodHandler(this);

    this.pathParser = basePathParser;
    this.npmProtocolFacade = npmProtocolFacade;
  }

  @Override
  public PathParser getPathParser() {
    return this.pathParser;
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of(
        "permission", Permission.READ, "writeOperation", false, "skipUsagePostProcessor", true);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.HEAD);
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    final var relativePath = ProtocolContextUtils.getRelativePath(context).getPath();

    if (relativePath.startsWith("/-/")) {
      return ResponseEntity.ok().build(); // whoami, ping, search, audit, dist-tags
    }

    final var tarball = DOWNLOAD_PATTERN.matcher(relativePath);

    if (tarball.matches()) {
      final var pathVars = ExtractPath.extractPathVars(tarball.group(1));

      return this.statusOf(
          this.npmProtocolFacade.tarballExists(
              context, pathVars.scopeName(), pathVars.packageName(), tarball.group(2)));
    }

    final var packagePath = PACKAGE_PATTERN.matcher(relativePath);

    if (packagePath.matches()) {
      final var pathVars = ExtractPath.extractPathVars(packagePath.group(1));

      return this.statusOf(
          this.npmProtocolFacade.packageExists(
              context, pathVars.scopeName(), pathVars.packageName()));
    }

    return ResponseEntity.ok().build(); // the repo itself
  }

  private ResponseEntity<Object> statusOf(final boolean exists) {

    return ResponseEntity.status(exists ? HttpStatus.OK : HttpStatus.NOT_FOUND).build();
  }
}
