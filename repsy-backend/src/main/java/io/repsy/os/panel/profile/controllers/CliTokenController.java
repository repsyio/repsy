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

import static org.springframework.http.HttpHeaders.AUTHORIZATION;

import io.repsy.core.web.http.NoStore;
import io.repsy.core.web.http.ResponseEntities;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.generated.model.AccessTokenCreated;
import io.repsy.os.generated.model.CliTokenForm;
import io.repsy.os.shared.auth.PanelAuthHelper;
import io.repsy.os.shared.token.services.PersonalAccessTokenService;
import io.repsy.os.shared.utils.MultiPortNames;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The temporary tokens of a CLI login (RPS-2039): what the authorize screen creates when the user
 * approves. Both routes take the access token of a login only, so no token, temporary or personal,
 * can mint or replace a temporary one.
 *
 * <p>Replacing the previous token is a second call, made once the new secret has been delivered, so
 * that a delivery the browser refused never costs the user a working token.
 */
@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/profile/cli-tokens")
class CliTokenController {

  private final PanelAuthHelper panelAuthHelper;
  private final PersonalAccessTokenService tokenService;

  @PostMapping
  public ResponseEntity<AccessTokenCreated> create(
      @RequestHeader(AUTHORIZATION) final String authHeader,
      @RequestBody @Valid final CliTokenForm form,
      final HttpServletResponse response) {

    final var userId = this.panelAuthHelper.authenticate(authHeader).getId();

    final var created = this.tokenService.createTemporaryToken(userId, form.getScopes());

    NoStore.apply(response);

    return ResponseEntities.created(
        URI.create("/api/profile/cli-tokens/" + created.getId()), created);
  }

  @PostMapping("/{tokenId}/actions/revoke-previous")
  public ResponseEntity<Void> revokePrevious(
      @RequestHeader(AUTHORIZATION) final String authHeader, @PathVariable final UUID tokenId) {

    final var userId = this.panelAuthHelper.authenticate(authHeader).getId();

    this.tokenService.revokePreviousTemporaryTokens(userId, tokenId);

    return ResponseEntities.noContent();
  }
}
