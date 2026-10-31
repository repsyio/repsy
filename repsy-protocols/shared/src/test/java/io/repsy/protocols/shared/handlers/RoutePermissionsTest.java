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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RoutePermissions (RPS-2212)")
class RoutePermissionsTest {

  @Test
  @DisplayName("returns the declared permission, NONE included")
  void declared() {
    assertThat(RoutePermissions.require(Map.of("permission", Permission.WRITE)))
        .isEqualTo(Permission.WRITE);
    assertThat(RoutePermissions.require(Map.of("permission", Permission.NONE)))
        .isEqualTo(Permission.NONE);
  }

  @Test
  @DisplayName(
      "find answers null for a missing or foreign value, require refuses with accessDenied")
  void undeclared() {
    assertThat(RoutePermissions.find(Map.of())).isNull();
    assertThat(RoutePermissions.find(Map.of("permission", "READ"))).isNull();
    final var properties = Map.<String, Object>of("writeOperation", false);

    assertThatThrownBy(() -> RoutePermissions.require(properties))
        .isInstanceOf(AccessNotAllowedException.class)
        .hasMessageContaining(ProtocolErrorCodes.ACCESS_DENIED);
  }
}
