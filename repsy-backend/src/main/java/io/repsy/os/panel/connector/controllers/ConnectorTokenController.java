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
package io.repsy.os.panel.connector.controllers;

import static org.springframework.data.domain.Sort.Direction.DESC;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;

import io.repsy.core.web.http.ResponseEntities;
import io.repsy.core.web.paging.SortValidator;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.shared.auth.PanelAuthHelper;
import io.repsy.os.shared.connector.services.ConnectorAuthorizationService;
import io.repsy.os.shared.token.dtos.PersonalAccessTokenListItem;
import io.repsy.os.shared.token.services.PersonalAccessTokenService;
import io.repsy.os.shared.utils.MultiPortNames;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The connected applications of a user (RPS-2660): their connector tokens, listed and revoked with
 * a login, and the route an application ends its own grant with.
 */
@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/profile/connector-tokens")
class ConnectorTokenController {

  /** The properties the list can be sorted by, as for the personal tokens. */
  private static final Set<String> SORT_PROPERTIES =
      Set.of("id", "name", "expirationDate", "lastUsedAt", "createdAt");

  private final PanelAuthHelper panelAuthHelper;
  private final ConnectorAuthorizationService authorizationService;
  private final PersonalAccessTokenService tokenService;

  @GetMapping
  public PagedModel<PersonalAccessTokenListItem> list(
      @RequestHeader(AUTHORIZATION) final String authHeader,
      @PageableDefault(sort = "id", direction = DESC) final Pageable pageable) {

    this.authorizationService.requireEnabled();

    final var userId = this.panelAuthHelper.authenticate(authHeader).getId();

    SortValidator.requireSortableBy(pageable, SORT_PROPERTIES);

    return new PagedModel<>(this.tokenService.getConnectorTokens(userId, pageable));
  }

  /** The application ends its own grant: the connector token of the request is revoked. */
  @DeleteMapping("/current")
  public ResponseEntity<Void> revokeCurrent(@RequestHeader(AUTHORIZATION) final String authHeader) {

    this.authorizationService.requireEnabled();

    final var token = this.panelAuthHelper.authenticateConnectorToken(authHeader);

    this.tokenService.revokeOwnConnectorToken(token);

    return ResponseEntities.noContent();
  }

  @DeleteMapping("/{tokenId}")
  public ResponseEntity<Void> revoke(
      @RequestHeader(AUTHORIZATION) final String authHeader, @PathVariable final UUID tokenId) {

    this.authorizationService.requireEnabled();

    final var userId = this.panelAuthHelper.authenticate(authHeader).getId();

    this.tokenService.revokeConnectorToken(userId, tokenId);

    return ResponseEntities.noContent();
  }
}
