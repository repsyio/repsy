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

import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.os.AbstractIT;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * RPS-2212: every protocol route registered in the real application declares a permission. A route
 * that is public says {@code Permission.NONE}; a route that says nothing is refused, so a missing
 * declaration fails here (and at startup, see {@code ProtocolRoutePermissionGuard}).
 */
@DisplayName("Protocol route permissions (RPS-2212)")
class ProtocolRoutePermissionIT extends AbstractIT {

  @Autowired private List<ProtocolMethodHandler> handlers;

  @Test
  @DisplayName("every registered protocol route declares a permission")
  void everyRouteDeclaresPermission() {

    assertThat(this.handlers).isNotEmpty();
    assertThat(ProtocolRoutePermissionGuard.routesWithoutPermission(this.handlers)).isEmpty();
  }
}
