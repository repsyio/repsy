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
package io.repsy.protocols.shared.handlers;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.libs.protocol.router.ProtocolProvider;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;

/**
 * The skeleton every protocol method handler repeated: registration with the provider, the
 * supported methods, the processor properties and a path parser that checks the method, delegates
 * to the protocol's base parser and tests the relative path. A handler passes a {@link
 * HandlerRoute} and only implements {@code handle}. A handler that works with a facade extends
 * {@link AbstractFacadeProtocolMethodHandler}.
 *
 * <p>The handler registers itself from this constructor, before the subclass constructor body has
 * run; registration only reads the route, so nothing of the subclass is needed yet.
 *
 * <p>The methods and the path test are the route's alone. A downstream handler (for example a Repsy
 * Cloud subclass that names its operation for a quota pre-processor, or asks for a configurable
 * permission) adds or replaces properties through {@link #additionalProperties()} instead of
 * overriding {@link #getProperties()}.
 */
@NullMarked
public abstract class AbstractRoutedProtocolMethodHandler implements ProtocolMethodHandler {

  private final HandlerRoute route;
  private final PathParser basePathParser;
  private final PathParser pathParser = this::route;

  /**
   * For a handler that builds its context itself and so overrides {@link #parse}; the base parser
   * is then never used.
   */
  protected AbstractRoutedProtocolMethodHandler(
      final HandlerRoute route, final ProtocolProvider provider) {

    this(route, _ -> Optional.empty(), provider);
  }

  protected AbstractRoutedProtocolMethodHandler(
      final HandlerRoute route, final PathParser basePathParser, final ProtocolProvider provider) {

    this.route = route;
    this.basePathParser = basePathParser;

    provider.registerMethodHandler(this);
  }

  @Override
  public final List<HttpMethod> getSupportedMethods() {
    return this.route.methods();
  }

  /**
   * The route's properties with {@link #additionalProperties()} on top: a key both set takes the
   * additional value. Without additional properties this is the route's own map, unchanged.
   */
  @Override
  public final Map<String, Object> getProperties() {
    return this.withAdditional(this.route.properties());
  }

  private Map<String, Object> withAdditional(final Map<String, Object> own) {
    final var additional = this.additionalProperties();

    if (additional.isEmpty()) {
      return own;
    }

    final var merged = new LinkedHashMap<>(own);
    merged.putAll(additional);

    return Map.copyOf(merged);
  }

  /**
   * Properties ({@link HandlerPropertyKeys}) a subclass adds to the route's, or replaces in it.
   * Read on every {@link #getProperties()} call, never from the constructor, so it may use fields
   * the subclass constructor set. It cannot remove a key or change the methods or the path. Empty
   * unless overridden.
   */
  protected Map<String, Object> additionalProperties() {
    return Map.of();
  }

  @Override
  public final boolean answersHead() {
    return this.route.answersHead();
  }

  @Override
  public final Map<String, Object> getHeadProperties() {
    final var headProperties = this.route.headProperties();

    return this.withAdditional(headProperties == null ? this.route.properties() : headProperties);
  }

  @Override
  public PathParser getPathParser() {
    return this.pathParser;
  }

  /** A HEAD the route answers is parsed like the GET it mirrors. */
  private HttpMethod effectiveMethod(final HttpServletRequest request) {
    final var requested = HttpMethod.valueOf(request.getMethod());

    return HttpMethod.HEAD.equals(requested) && this.route.answersHead()
        ? HttpMethod.GET
        : requested;
  }

  private Optional<ProtocolContext> route(final HttpServletRequest request) {
    final var method = this.effectiveMethod(request);

    if (!this.accepts(method, request)) {
      return Optional.empty();
    }

    final var parsed = this.parse(request);

    if (parsed.isEmpty() || this.route.path() == null) {
      return parsed;
    }

    final var relativePath = ProtocolContextUtils.getRelativePath(parsed.get()).getPath();

    return this.matches(method, relativePath) ? parsed : Optional.empty();
  }

  /** The parser the protocol builds its contexts with. */
  protected final PathParser basePathParser() {
    return this.basePathParser;
  }

  /** Whether the request is for this handler at all, before any path is parsed. */
  protected boolean accepts(final HttpMethod method, final HttpServletRequest request) {
    return this.route.methods().contains(method);
  }

  /**
   * Builds the context of the request; the protocol's base parser unless a handler knows better.
   */
  protected Optional<ProtocolContext> parse(final HttpServletRequest request) {
    return this.basePathParser.parse(request);
  }

  /** Whether the relative path (and method) belongs to this handler. */
  protected boolean matches(final HttpMethod method, final String relativePath) {
    final var path = this.route.path();
    return path == null || path.test(relativePath);
  }
}
