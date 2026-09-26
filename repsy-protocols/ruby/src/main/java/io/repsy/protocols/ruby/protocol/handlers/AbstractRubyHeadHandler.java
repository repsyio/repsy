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
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * Answers {@code HEAD} on any Ruby path with the same status the matching {@code GET} route would
 * give: {@code 200} for a resolvable resource, {@code 404} otherwise (RPS-1237). Every check is an
 * existence-only lookup on {@link RubyProtocolFacade} &mdash; never {@code getGemInfo} (builds the
 * whole body) or {@code downloadGem} (opens the storage resource).
 *
 * <p>The path patterns below intentionally mirror, rather than share, the private patterns in
 * {@link AbstractRubyCompactIndexInfoHandler}, {@link AbstractRubyGemDownloadHandler} and {@link
 * AbstractRubyGemspecHandler}: those GET handlers keep their own copies, so this class keeps its
 * own rather than reaching into them.
 */
@NullMarked
public abstract class AbstractRubyHeadHandler implements ProtocolMethodHandler {

  private static final Set<String> ALWAYS_EXISTING_PATHS =
      Set.of(
          "/versions",
          "/names",
          "/specs.4.8.gz",
          "/latest_specs.4.8.gz",
          "/prerelease_specs.4.8.gz");
  private static final Pattern INFO_PATTERN = Pattern.compile("^/info/(.+)$");
  private static final Pattern DOWNLOAD_PATTERN = Pattern.compile("^/gems/(.+\\.gem)$");
  private static final Pattern GEMSPEC_PATTERN =
      Pattern.compile("^/quick/Marshal\\.4\\.8/(.+)\\.gemspec\\.rz$");

  private final PathParser pathParser;
  private final RubyProtocolFacade facade;

  protected AbstractRubyHeadHandler(
      final PathParser basePathParser,
      final RubyProtocolFacade facade,
      final RubyProtocolProvider provider) {
    this.pathParser = basePathParser;
    this.facade = facade;
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
    return this.pathParser;
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {
    final var relativePath = ProtocolContextUtils.getRelativePath(context).getPath();
    if (!this.exists(context, relativePath)) {
      return ResponseEntity.notFound().build();
    }

    // The header the GET of this path sends (RPS-1442).
    final var contentDisposition = RubyContentDisposition.forPath(relativePath);
    final var ok = ResponseEntity.ok();

    if (contentDisposition != null) {
      ok.header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition);
    }

    return ok.build();
  }

  private boolean exists(final ProtocolContext context, final String relativePath) {
    if (ALWAYS_EXISTING_PATHS.contains(relativePath)) {
      return true;
    }

    final var infoMatcher = INFO_PATTERN.matcher(relativePath);
    if (infoMatcher.matches()) {
      return this.facade.gemExists(context, infoMatcher.group(1));
    }

    final var downloadMatcher = DOWNLOAD_PATTERN.matcher(relativePath);
    if (downloadMatcher.matches()) {
      return this.facade.gemFileExists(context, downloadMatcher.group(1));
    }

    final var gemspecMatcher = GEMSPEC_PATTERN.matcher(relativePath);
    if (gemspecMatcher.matches()) {
      final var parsed = parseGemspecFilename(gemspecMatcher.group(1));
      return parsed != null && this.facade.gemspecExists(context, parsed[0], parsed[1]);
    }

    return false;
  }

  /**
   * Same name/version split {@link AbstractRubyGemspecHandler} uses for the GET route: the last
   * {@code -<digit>} boundary gives the name (so a hyphen-digit gem name resolves correctly), and a
   * trailing non-digit-initial {@code -<segment>} is a platform suffix stripped from the version.
   */
  private static String @Nullable [] parseGemspecFilename(final String fullName) {
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
      if (fullName.charAt(i) == '-' && Character.isDigit(fullName.charAt(i + 1))) {
        return i;
      }
    }
    return -1;
  }

  private static String stripPlatformSuffix(final String versionWithPlatform) {
    final var dash = versionWithPlatform.lastIndexOf('-');
    if (dash > 0 && !Character.isDigit(versionWithPlatform.charAt(dash + 1))) {
      return versionWithPlatform.substring(0, dash);
    }
    return versionWithPlatform;
  }
}
