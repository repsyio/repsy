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

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.web.http.NoStore;
import io.repsy.core.web.http.ResponseEntities;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.generated.model.AccessTokenCreated;
import io.repsy.os.generated.model.ConnectorTokenExchangeForm;
import io.repsy.os.shared.connector.services.ConnectorAuthorizationService;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.shared.auth.AuthFailureThrottle;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The exchange of a one-time consent code for a connector token (RPS-2660), called server to server
 * by the connected application. The code is the only credential, so a failed exchange counts
 * against the client like a wrong password, and a client that has used up its failures is refused
 * before the code is looked up.
 */
@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/connector/tokens")
class ConnectorTokenExchangeController {

  private final ConnectorAuthorizationService authorizationService;
  private final AuthFailureThrottle authFailureThrottle;

  @PostMapping
  public ResponseEntity<AccessTokenCreated> exchange(
      @RequestBody @Valid final ConnectorTokenExchangeForm form,
      final HttpServletResponse response) {

    this.authorizationService.requireEnabled();
    this.authFailureThrottle.checkAllowed();

    final AccessTokenCreated created;

    try {
      created = this.authorizationService.exchange(form);
    } catch (final BadRequestException e) {
      if (ErrorConstants.INVALID_CONNECTOR_CODE.equals(e.publicCode())) {
        this.authFailureThrottle.recordFailure();
      }

      throw e;
    }

    NoStore.apply(response);

    return ResponseEntities.created(
        URI.create("/api/profile/connector-tokens/" + created.getId()), created);
  }
}
