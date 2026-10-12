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

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Reads the permission a protocol route declares and fails closed when it declares none (RPS-2212,
 * the counterpart of Cloud RPS-2189).
 *
 * <p>A route that needs no permission says so with {@link Permission#NONE} (npm login, Docker token
 * and registry check, Cargo me): that is the one explicit public marker. A route whose properties
 * carry no permission at all is a registration mistake, never "no restriction": the request is
 * refused with {@code accessDenied}, and the startup guard stops the application from starting with
 * such a route registered.
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RoutePermissions {

  /** The permission the route declares, or {@code null} when it declares none. */
  public static @Nullable Permission find(final Map<String, Object> properties) {

    return properties.get(HandlerPropertyKeys.PERMISSION) instanceof final Permission permission
        ? permission
        : null;
  }

  /**
   * The permission the route declares.
   *
   * @throws AccessNotAllowedException when the route declares none (fail closed)
   */
  public static Permission require(final Map<String, Object> properties) {

    final var permission = find(properties);

    if (permission == null) {
      log.error("A protocol route declares no permission; refusing the request: {}", properties);
      throw new AccessNotAllowedException(ProtocolErrorCodes.ACCESS_DENIED);
    }

    return permission;
  }
}
