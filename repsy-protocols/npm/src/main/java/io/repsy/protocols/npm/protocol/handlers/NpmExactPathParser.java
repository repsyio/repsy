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
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;

/**
 * Path parser of the registry endpoints below {@code /-/} ({@code whoami}, {@code ping}, {@code
 * v1/search} and the audit endpoints). It claims a request only when the HTTP method and the whole
 * relative path match, so a package that is literally named {@code -}, {@code whoami} or {@code
 * search} keeps going to the package handlers.
 *
 * <p>The servlet path is checked before the base parser runs, because the base parser looks the
 * repository up in the database and an unrelated npm request must not pay for that once more per
 * handler.
 */
@NullMarked
final class NpmExactPathParser implements PathParser {

  private final PathParser basePathParser;
  private final HttpMethod method;
  private final Pattern servletPathPattern;
  private final Pattern relativePathPattern;

  /**
   * @param basePathParser The parser that resolves the repository
   * @param method The only HTTP method the endpoint answers
   * @param relativePathRegex The regex of the path below the repository name, without anchors and
   *     starting with a slash, e.g. {@code /-/whoami}
   */
  NpmExactPathParser(
      final PathParser basePathParser, final HttpMethod method, final String relativePathRegex) {
    this.basePathParser = basePathParser;
    this.method = method;
    this.servletPathPattern = Pattern.compile("^/[^/]+" + relativePathRegex + "$");
    this.relativePathPattern = Pattern.compile("^" + relativePathRegex + "$");
  }

  @Override
  public Optional<ProtocolContext> parse(final HttpServletRequest request) {
    if (!this.method.equals(HttpMethod.valueOf(request.getMethod()))) {
      return Optional.empty();
    }

    if (!this.servletPathPattern.matcher(request.getServletPath()).matches()) {
      return Optional.empty();
    }

    final var parsedPathOpt = this.basePathParser.parse(request);
    if (parsedPathOpt.isEmpty()) {
      return Optional.empty();
    }

    final var relativePath = ProtocolContextUtils.getRelativePath(parsedPathOpt.get()).getPath();

    if (!this.relativePathPattern.matcher(relativePath).matches()) {
      return Optional.empty();
    }

    return parsedPathOpt;
  }
}
