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

import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;

/**
 * What one protocol method handler answers to: the HTTP methods, the properties the processors read
 * ({@link HandlerPropertyKeys}) and, optionally, a test on the repository relative path.
 *
 * <p>A key that a handler never set stays absent from {@link #properties()}, exactly as it was in
 * the {@code Map.of(...)} the handlers used to build by hand, so a processor that distinguishes
 * "absent" from {@code false} sees the same map as before.
 *
 * @param methods the HTTP methods the handler is registered for
 * @param properties the immutable {@code getProperties()} map
 * @param path accepts the relative path (the part after the repository), or {@code null} when the
 *     path parser the handler is built with already decides
 */
@NullMarked
public record HandlerRoute(
    List<HttpMethod> methods, Map<String, Object> properties, @Nullable Predicate<String> path) {

  public HandlerRoute {
    methods = List.copyOf(methods);
    properties = Map.copyOf(properties);
  }

  /** A route that needs {@code permission} and sets no other property. */
  public static HandlerRoute of(final Permission permission, final HttpMethod... methods) {
    return new HandlerRoute(
        List.of(methods), Map.of(HandlerPropertyKeys.PERMISSION, permission), null);
  }

  /** A read route: {@link Permission#READ} and {@code writeOperation=false}. */
  public static HandlerRoute read(final HttpMethod... methods) {
    return of(Permission.READ, methods).writeOperation(false);
  }

  /** A write route: {@link Permission#WRITE} and {@code writeOperation=true}. */
  public static HandlerRoute write(final HttpMethod... methods) {
    return of(Permission.WRITE, methods).writeOperation(true);
  }

  public HandlerRoute writeOperation(final boolean value) {
    return this.with(HandlerPropertyKeys.WRITE_OPERATION, value);
  }

  public HandlerRoute skipPreProcessor(final boolean value) {
    return this.with(HandlerPropertyKeys.SKIP_PRE_PROCESSOR, value);
  }

  public HandlerRoute skipHeaderPreProcessor(final boolean value) {
    return this.with(HandlerPropertyKeys.SKIP_HEADER_PRE_PROCESSOR, value);
  }

  public HandlerRoute skipUsagePostProcessor(final boolean value) {
    return this.with(HandlerPropertyKeys.SKIP_USAGE_POST_PROCESSOR, value);
  }

  public HandlerRoute method(final String value) {
    return this.with(HandlerPropertyKeys.METHOD, value);
  }

  /** The same route that only accepts the relative paths {@code path} accepts. */
  public HandlerRoute path(final Predicate<String> relativePathTest) {
    return new HandlerRoute(this.methods, this.properties, relativePathTest);
  }

  private HandlerRoute with(final String key, final Object value) {
    final var copy = new LinkedHashMap<>(this.properties);
    copy.put(key, value);
    return new HandlerRoute(this.methods, copy, this.path);
  }
}
