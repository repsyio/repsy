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
import io.repsy.protocols.ruby.protocol.facades.contract.RubyProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@NullMarked
public abstract class AbstractRubyGemYankHandler implements ProtocolMethodHandler {

  private static final String YANK_PATH = "/api/v1/gems/yank";
  private static final String DEFAULT_PLATFORM = "ruby";

  private final PathParser basePathParser;
  private final RubyProtocolFacade facade;

  protected AbstractRubyGemYankHandler(
      final PathParser basePathParser,
      final RubyProtocolFacade facade,
      final RubyProtocolProvider provider) {
    this.basePathParser = basePathParser;
    this.facade = facade;
    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.DELETE);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of("permission", Permission.MANAGE, "writeOperation", true);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      if (!HttpMethod.DELETE.name().equals(request.getMethod())) {
        return Optional.empty();
      }
      final var parsedOpt = this.basePathParser.parse(request);
      if (parsedOpt.isEmpty()) {
        return Optional.empty();
      }
      final var relativePath = ProtocolContextUtils.getRelativePath(parsedOpt.get()).getPath();
      return YANK_PATH.equals(relativePath) ? parsedOpt : Optional.empty();
    };
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {
    // gem yank sends form-encoded body; Spring Boot's FormContentFilter makes params available
    final var gemName = request.getParameter("gem_name");
    final var version = request.getParameter("version");
    final var platform = platformOrDefault(request.getParameter("platform"));

    if (gemName == null || gemName.isBlank() || version == null || version.isBlank()) {
      return ResponseEntity.badRequest()
          .contentType(MediaType.TEXT_PLAIN)
          .body("gem_name and version are required");
    }

    this.facade.yankGem(context, gemName, version, platform);

    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_PLAIN)
        .body("Successfully yanked gem: " + gemName + " (" + version + ")");
  }

  private static String platformOrDefault(final @Nullable String platform) {
    return (platform == null || platform.isBlank()) ? DEFAULT_PLATFORM : platform;
  }
}
