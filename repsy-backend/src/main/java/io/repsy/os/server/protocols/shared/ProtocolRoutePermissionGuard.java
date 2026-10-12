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
package io.repsy.os.server.protocols.shared;

import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.RoutePermissions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * RPS-2212: stops the application from starting when a registered protocol route declares no
 * permission. Fail fast at registration instead of per request: such a route used to be answered as
 * if it had no restriction. A route that needs none says {@code Permission.NONE}, the explicit
 * public marker ({@link RoutePermissions}); the request path refuses a route without one as well.
 */
@Component
@RequiredArgsConstructor
public class ProtocolRoutePermissionGuard implements SmartInitializingSingleton {

  private final List<ProtocolMethodHandler> handlers;

  @Override
  public void afterSingletonsInstantiated() {

    final var lacking = routesWithoutPermission(this.handlers);

    if (!lacking.isEmpty()) {
      throw new IllegalStateException(
          "Protocol routes without a declared permission (use Permission.NONE for a public one): "
              + lacking);
    }
  }

  /**
   * The registered routes (the handler and, when it answers {@code HEAD} through the fallback, its
   * HEAD properties) whose properties carry no permission.
   */
  public static List<String> routesWithoutPermission(
      final Collection<ProtocolMethodHandler> handlers) {

    final var lacking = new ArrayList<String>();

    for (final var handler : handlers) {
      final var name = handler.getClass().getName();

      if (RoutePermissions.find(handler.getProperties()) == null) {
        lacking.add(name);
      }

      if (handler.answersHead() && RoutePermissions.find(handler.getHeadProperties()) == null) {
        lacking.add(name + " (HEAD)");
      }
    }

    return lacking;
  }
}
