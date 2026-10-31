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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

@DisplayName("ProtocolRoutePermissionGuard (RPS-2212)")
class ProtocolRoutePermissionGuardTest {

  private record Route(Map<String, Object> properties, Map<String, Object> headProperties)
      implements ProtocolMethodHandler {

    @Override
    public List<HttpMethod> getSupportedMethods() {
      return List.of(HttpMethod.GET);
    }

    @Override
    public Map<String, Object> getProperties() {
      return this.properties;
    }

    @Override
    public boolean answersHead() {
      return this.headProperties != null;
    }

    @Override
    public Map<String, Object> getHeadProperties() {
      return this.headProperties == null ? this.properties : this.headProperties;
    }

    @Override
    public PathParser getPathParser() {
      return _ -> java.util.Optional.empty();
    }

    @Override
    public ResponseEntity<Object> handle(
        final io.repsy.libs.protocol.router.ProtocolContext parsedPath,
        final HttpServletRequest request,
        final HttpServletResponse response) {
      return ResponseEntity.ok().build();
    }
  }

  private static Route route(final Map<String, Object> properties) {
    return new Route(properties, null);
  }

  @Test
  @DisplayName("routes that declare a permission, NONE included, pass")
  void declaredRoutesPass() {

    final var handlers =
        List.<ProtocolMethodHandler>of(
            route(Map.of("permission", Permission.READ)),
            route(Map.of("permission", Permission.NONE)));

    assertThat(ProtocolRoutePermissionGuard.routesWithoutPermission(handlers)).isEmpty();
    assertThatCode(() -> new ProtocolRoutePermissionGuard(handlers).afterSingletonsInstantiated())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a route without a permission, or whose HEAD has none, fails startup")
  void undeclaredRouteFailsStartup() {

    final var handlers =
        List.<ProtocolMethodHandler>of(
            route(Map.of("writeOperation", false)),
            new Route(Map.of("permission", Permission.READ), Map.of("writeOperation", false)));

    assertThat(ProtocolRoutePermissionGuard.routesWithoutPermission(handlers)).hasSize(2);
    final var guard = new ProtocolRoutePermissionGuard(handlers);

    assertThatThrownBy(guard::afterSingletonsInstantiated)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Permission.NONE")
        .hasMessageContaining("(HEAD)");
  }
}
