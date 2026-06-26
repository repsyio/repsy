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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@NullMarked
public abstract class AbstractRubySpecsIndexHandler implements ProtocolMethodHandler {

  private static final String SPECS_PATH = "/specs.4.8.gz";
  private static final String LATEST_SPECS_PATH = "/latest_specs.4.8.gz";
  private static final String PRERELEASE_SPECS_PATH = "/prerelease_specs.4.8.gz";
  private static final Set<String> SPECS_PATHS =
      Set.of(SPECS_PATH, LATEST_SPECS_PATH, PRERELEASE_SPECS_PATH);

  private final PathParser basePathParser;
  private final RubyProtocolFacade facade;

  protected AbstractRubySpecsIndexHandler(
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
      return SPECS_PATHS.contains(relativePath) ? parsedOpt : Optional.empty();
    };
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {
    final var relativePath = ProtocolContextUtils.getRelativePath(context).getPath();
    final var raw = this.resolveSpecs(context, relativePath);
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(deflate(raw));
  }

  private byte[] resolveSpecs(final ProtocolContext context, final String relativePath) {
    if (LATEST_SPECS_PATH.equals(relativePath)) {
      return this.facade.getLatestSpecs(context);
    }
    if (PRERELEASE_SPECS_PATH.equals(relativePath)) {
      return this.facade.getPrereleaseSpecs(context);
    }
    return this.facade.getSpecs(context);
  }

  private static byte[] deflate(final byte[] raw) {
    try {
      final var out = new ByteArrayOutputStream(raw.length);
      try (final var deflate = new DeflaterOutputStream(out)) {
        deflate.write(raw);
      }
      return out.toByteArray();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
