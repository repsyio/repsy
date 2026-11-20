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

import static org.springframework.http.HttpHeaders.AUTHORIZATION;

import io.repsy.core.web.http.NoStore;
import io.repsy.core.web.http.ResponseEntities;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.generated.model.ConnectorAuthorizationForm;
import io.repsy.os.generated.model.ConnectorAuthorizationInfo;
import io.repsy.os.shared.auth.PanelAuthHelper;
import io.repsy.os.shared.connector.services.ConnectorAuthorizationService;
import io.repsy.os.shared.utils.MultiPortNames;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The consent of a connected application (RPS-2660): the {@code /connector/auth} page calls it when
 * the signed-in user approves, and hands the one-time code of the answer to the application. It
 * takes the access token of a login only, so no token can grant another.
 */
@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/profile/connector-authorizations")
class ConnectorAuthorizationController {

  private final PanelAuthHelper panelAuthHelper;
  private final ConnectorAuthorizationService authorizationService;

  @PostMapping
  public ResponseEntity<ConnectorAuthorizationInfo> create(
      @RequestHeader(AUTHORIZATION) final String authHeader,
      @RequestBody @Valid final ConnectorAuthorizationForm form,
      final HttpServletResponse response) {

    this.authorizationService.requireEnabled();

    final var userId = this.panelAuthHelper.authenticate(authHeader).getId();
    final var created = this.authorizationService.authorize(userId, form);

    NoStore.apply(response);

    return ResponseEntities.created(
        URI.create("/api/profile/connector-authorizations/" + created.getId()), created);
  }
}
