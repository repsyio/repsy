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
package io.repsy.protocols.pypi.protocol.handlers;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.pypi.protocol.PypiProtocolProvider;
import io.repsy.protocols.pypi.protocol.facades.PypiProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Answers {@code HEAD} on a PyPI path with the same status the matching {@code GET} route would
 * give: {@code 200} for a resolvable resource, {@code 404} otherwise (RPS-1226). Every check is an
 * existence-only lookup on {@link PypiProtocolFacade} &mdash; never {@code getPackageList} (builds
 * the whole body) or {@code downloadArchiveFile} (opens the storage resource).
 *
 * <p>The path patterns below intentionally mirror, rather than share, the private patterns in
 * {@link AbstractPypiSimpleProtocolMethodHandler} and {@link
 * AbstractPypiFileDownloadProtocolMethodHandler}: those GET handlers keep their own copies, so this
 * class keeps its own rather than reaching into them.
 *
 * <p>A non-normalized project name (e.g. {@code /simple/My_Pkg/}) mirrors the GET route's {@code
 * 307} redirect to the normalized URI, rather than answering the existence check directly against
 * the raw name: {@code HEAD} should mean the same thing a client's follow-up {@code GET} would.
 */
@NullMarked
public abstract class AbstractPypiHeadProtocolMethodHandler<ID> implements ProtocolMethodHandler {

  private static final Pattern SIMPLE_PATTERN = Pattern.compile("^/simple(?:/([^/]+))?/?$");
  private static final Pattern DOWNLOAD_PATTERN = Pattern.compile("^/([^/]+)/-/([^/]+)$");

  private final PathParser pathParser;
  private final PypiProtocolFacade<ID> facade;

  protected AbstractPypiHeadProtocolMethodHandler(
      final PathParser pathParser,
      final PypiProtocolFacade<ID> facade,
      final PypiProtocolProvider provider) {

    provider.registerMethodHandler(this);

    this.pathParser = pathParser;
    this.facade = facade;
  }

  protected abstract @Nullable URI getNormalizedUri(
      HttpServletRequest request, @Nullable String packageName);

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

    final var simpleMatcher = SIMPLE_PATTERN.matcher(relativePath);
    if (simpleMatcher.matches()) {
      return this.handleSimple(context, request, simpleMatcher.group(1));
    }

    final var downloadMatcher = DOWNLOAD_PATTERN.matcher(relativePath);
    if (downloadMatcher.matches()) {
      final var exists =
          this.facade.archiveFileExists(
              context, downloadMatcher.group(1), downloadMatcher.group(2));
      return exists ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    return ResponseEntity.notFound().build();
  }

  private ResponseEntity<Object> handleSimple(
      final ProtocolContext context,
      final HttpServletRequest request,
      final @Nullable String packageName) {

    if (packageName == null) {
      // The path parser already guarantees the repo exists for `/simple/`.
      return ResponseEntity.ok().build();
    }

    final var normalizedUri = this.getNormalizedUri(request, packageName);
    if (normalizedUri != null) {
      return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT).location(normalizedUri).build();
    }

    return this.facade.packageExists(context, packageName)
        ? ResponseEntity.ok().build()
        : ResponseEntity.notFound().build();
  }
}
