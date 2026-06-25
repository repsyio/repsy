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
import java.util.regex.Pattern;
import java.util.zip.DeflaterOutputStream;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@NullMarked
public abstract class AbstractRubyGemspecHandler implements ProtocolMethodHandler {

  private static final Pattern GEMSPEC_PATH_PATTERN =
      Pattern.compile("^/quick/Marshal\\.4\\.8/(.+\\.gemspec\\.rz)$");

  private final PathParser basePathParser;
  private final RubyProtocolFacade facade;

  protected AbstractRubyGemspecHandler(
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
      return GEMSPEC_PATH_PATTERN.matcher(relativePath).matches() ? parsedOpt : Optional.empty();
    };
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {
    final var relativePath = ProtocolContextUtils.getRelativePath(context).getPath();
    final var matcher = GEMSPEC_PATH_PATTERN.matcher(relativePath);
    if (!matcher.matches()) {
      return ResponseEntity.notFound().build();
    }
    final var parsed = parseGemspecFilename(matcher.group(1));
    if (parsed == null) {
      return ResponseEntity.notFound().build();
    }
    try {
      final var raw = this.facade.getGemspec(context, parsed[0], parsed[1]);
      return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(deflate(raw));
    } catch (final Exception e) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
  }

  private static @Nullable String[] parseGemspecFilename(final String filename) {
    if (!filename.endsWith(".gemspec.rz")) {
      return null;
    }
    final var fullName = filename.substring(0, filename.length() - ".gemspec.rz".length());
    final var nameEnd = findNameVersionBoundary(fullName);
    if (nameEnd < 0) {
      return null;
    }
    final var name = fullName.substring(0, nameEnd);
    final var version = stripPlatformSuffix(fullName.substring(nameEnd + 1));
    return new String[] {name, version};
  }

  private static int findNameVersionBoundary(final String fullName) {
    for (var i = fullName.length() - 2; i >= 1; i--) {
      if (isVersionBoundary(fullName, i)) {
        return i;
      }
    }
    return -1;
  }

  private static boolean isVersionBoundary(final String s, final int i) {
    return s.charAt(i) == '-' && Character.isDigit(s.charAt(i + 1));
  }

  private static String stripPlatformSuffix(final String versionWithPlatform) {
    final var dash = versionWithPlatform.lastIndexOf('-');
    if (dash > 0 && !Character.isDigit(versionWithPlatform.charAt(dash + 1))) {
      return versionWithPlatform.substring(0, dash);
    }
    return versionWithPlatform;
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
