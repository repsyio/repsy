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
package io.repsy.os.panel.profile.controllers;

import static org.springframework.data.domain.Sort.Direction.DESC;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;

import io.repsy.core.web.http.NoStore;
import io.repsy.core.web.http.ResponseEntities;
import io.repsy.core.web.paging.SortValidator;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.generated.model.AccessTokenCreated;
import io.repsy.os.generated.model.AccessTokenForm;
import io.repsy.os.generated.model.AccessTokenWhoAmI;
import io.repsy.os.shared.auth.PanelAuthHelper;
import io.repsy.os.shared.token.dtos.PersonalAccessTokenListItem;
import io.repsy.os.shared.token.services.PersonalAccessTokenService;
import io.repsy.os.shared.utils.MultiPortNames;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The personal access tokens of the signed-in user. Creating, listing and revoking work with the
 * access token of a login only, so a token cannot mint or revoke tokens (the other three routes
 * answer 401 to one); {@link #current} is the one route a personal access token may call. The
 * secret of a token is shown by {@link #create} only.
 */
@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/profile/access-tokens")
class PersonalAccessTokenController {

  /**
   * The properties of a listed token the list can be sorted by. The hash and the scopes are left
   * out: the first is never shown, and a list ordered by a comma separated column means nothing.
   */
  private static final Set<String> SORT_PROPERTIES =
      Set.of("id", "name", "expirationDate", "lastUsedAt", "createdAt");

  private final PanelAuthHelper panelAuthHelper;
  private final PersonalAccessTokenService tokenService;

  @PostMapping
  public ResponseEntity<AccessTokenCreated> create(
      @RequestHeader(AUTHORIZATION) final String authHeader,
      @RequestBody @Valid final AccessTokenForm form,
      final HttpServletResponse response) {

    final var userId = this.panelAuthHelper.authenticate(authHeader).getId();

    final var created = this.tokenService.createToken(userId, form);

    NoStore.apply(response);

    return ResponseEntities.created(
        URI.create("/api/profile/access-tokens/" + created.getId()), created);
  }

  @GetMapping
  public PagedModel<PersonalAccessTokenListItem> list(
      @RequestHeader(AUTHORIZATION) final String authHeader,
      @PageableDefault(sort = "id", direction = DESC) final Pageable pageable) {

    final var userId = this.panelAuthHelper.authenticate(authHeader).getId();

    SortValidator.requireSortableBy(pageable, SORT_PROPERTIES);

    return new PagedModel<>(this.tokenService.getTokens(userId, pageable));
  }

  /**
   * What the token of this request is. The one route here that takes a personal access token: it
   * answers who the token belongs to, what it may do and when it stops working. With the access
   * token of a login it answers {@code notAnAccessToken} (400), as there is no token to describe.
   */
  @GetMapping("/current")
  public AccessTokenWhoAmI current(@RequestHeader(AUTHORIZATION) final String authHeader) {

    final var token = this.panelAuthHelper.authenticateAccessToken(authHeader);

    return this.tokenService.getWhoAmI(token.id());
  }

  @DeleteMapping("/{tokenId}")
  public ResponseEntity<Void> revoke(
      @RequestHeader(AUTHORIZATION) final String authHeader, @PathVariable final UUID tokenId) {

    final var userId = this.panelAuthHelper.authenticate(authHeader).getId();

    this.tokenService.revokeToken(userId, tokenId);

    return ResponseEntities.noContent();
  }
}
