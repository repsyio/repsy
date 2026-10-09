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
package io.repsy.protocols.ruby.protocol.handlers;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.ruby.protocol.RubyProtocolProvider;
import io.repsy.protocols.ruby.protocol.facades.contracts.RubyProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * {@code GET /api/v1/dependencies?gems=name1,name2}: the legacy Marshal dependency-resolution
 * endpoint older RubyGems clients and some tools (including some Bundler resolution paths) still
 * call, backed by {@link io.repsy.protocols.ruby.shared.utils.RubyMarshalWriter#dumpDependencies}
 * (RPS-1554). Before this handler existed, the request matched no route and answered {@code 404
 * unknownPath}, so {@code dumpDependencies} was dead code with nothing serving it.
 */
@NullMarked
public abstract class AbstractRubyDependenciesHandler implements ProtocolMethodHandler {

  private static final String DEPENDENCIES_PATH = "/api/v1/dependencies";
  private static final String GEMS_PARAM = "gems";

  private final PathParser basePathParser;
  private final RubyProtocolFacade facade;

  protected AbstractRubyDependenciesHandler(
      final PathParser basePathParser,
      final RubyProtocolFacade facade,
      final RubyProtocolProvider provider) {
    this.basePathParser = basePathParser;
    this.facade = facade;
    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.GET);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of("permission", Permission.READ, "writeOperation", false);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      if (!HttpMethod.GET.name().equals(request.getMethod())) {
        return Optional.empty();
      }
      final var parsedOpt = this.basePathParser.parse(request);
      if (parsedOpt.isEmpty()) {
        return Optional.empty();
      }
      final var relativePath = ProtocolContextUtils.getRelativePath(parsedOpt.get()).getPath();
      return DEPENDENCIES_PATH.equals(relativePath) ? parsedOpt : Optional.empty();
    };
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {
    final var gemNames = parseGemNames(request.getParameter(GEMS_PARAM));
    final var body = this.facade.getDependencies(context, gemNames);
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(body);
  }

  /**
   * {@code gems} is a comma-separated list of names, matching the real RubyGems API; a missing or
   * blank value is an empty request (no gems to resolve), not an error.
   */
  private static List<String> parseGemNames(final @Nullable String gems) {
    if (gems == null || gems.isBlank()) {
      return List.of();
    }
    return Arrays.stream(gems.split(","))
        .map(String::trim)
        .filter(name -> !name.isEmpty())
        .toList();
  }
}
