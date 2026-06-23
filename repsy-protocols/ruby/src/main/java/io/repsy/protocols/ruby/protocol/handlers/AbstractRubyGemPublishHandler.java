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
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@NullMarked
public abstract class AbstractRubyGemPublishHandler implements ProtocolMethodHandler {

  private static final String PUBLISH_PATH = "/api/v1/gems";
  private static final String GEM_NAME = "gemName";
  private static final String GEM_VERSION = "gemVersion";

  private final PathParser basePathParser;
  private final RubyProtocolFacade facade;

  protected AbstractRubyGemPublishHandler(
      final PathParser basePathParser,
      final RubyProtocolFacade facade,
      final RubyProtocolProvider provider) {
    this.basePathParser = basePathParser;
    this.facade = facade;
    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.POST);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of("permission", Permission.WRITE, "writeOperation", true);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      if (!HttpMethod.POST.name().equals(request.getMethod())) {
        return Optional.empty();
      }
      final var parsedOpt = this.basePathParser.parse(request);
      if (parsedOpt.isEmpty()) {
        return Optional.empty();
      }
      final var relativePath = ProtocolContextUtils.getRelativePath(parsedOpt.get()).getPath();
      return PUBLISH_PATH.equals(relativePath) ? parsedOpt : Optional.empty();
    };
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {
    try {
      final var gemBytes = request.getInputStream().readAllBytes();
      this.facade.publishGem(context, gemBytes);
      final var gemName = (String) context.getProperty(GEM_NAME);
      final var gemVersion = (String) context.getProperty(GEM_VERSION);
      return ResponseEntity.ok()
          .contentType(MediaType.TEXT_PLAIN)
          .body("Successfully registered gem: " + gemName + " (" + gemVersion + ")");
    } catch (final IOException e) {
      return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body(e.getMessage());
    }
  }
}
