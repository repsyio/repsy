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
package io.repsy.os.server.protocols.cargo.protocol.facades;

import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.protocols.cargo.protocol.facade.AbstractCargoProtocolFacade;
import io.repsy.protocols.cargo.shared.crate.services.CargoCrateService;
import io.repsy.protocols.cargo.shared.storage.services.CargoStorageService;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@NullMarked
@Component
public class CargoProtocolFacade extends AbstractCargoProtocolFacade<UUID> {

  // private final JwtUtils jwtUtils;

  public CargoProtocolFacade(
      final CargoStorageService cargoStorageService,
      final CargoCrateService<UUID> cargoCrateService,
      final ObjectMapper objectMapper,
      final JwtUtils jwtUtils) {

    super(cargoStorageService, cargoCrateService, objectMapper);
    // this.jwtUtils = jwtUtils;
  }

  //  @Override
  //  public List<CargoOwnerItem> listOwners(
  //      final ProtocolContext context, final @Nullable String authHeader) {
  //
  //    if (authHeader == null) {
  //      return List.of();
  //    }
  //
  //    try {
  //      final String username;
  //
  //      if (isBasicToken(authHeader)) {
  //        final var credentials = extractCredentialsFromAuthHeader(authHeader);
  //        username = credentials != null ? credentials.getUsername() : null;
  //      } else {
  //        final var normalized = isBearerToken(authHeader) ? authHeader : "Bearer " + authHeader;
  //        username = this.jwtUtils.verifyAndExtractUsername(normalized);
  //      }
  //
  //      if (username == null || username.isBlank()) {
  //        return List.of();
  //      }
  //
  //      return List.of(new CargoOwnerItem(0, username, username));
  //
  //    } catch (final Exception e) {
  //      return List.of();
  //    }
  //  }
}
