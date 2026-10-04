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

import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.generated.model.LoginInfo;
import io.repsy.os.generated.model.PasswordForm;
import io.repsy.os.generated.model.ProfileInfo;
import io.repsy.os.generated.model.UpdateUsernameForm;
import io.repsy.os.panel.profile.services.ProfileService;
import io.repsy.os.shared.auth.PanelAuthHelper;
import io.repsy.os.shared.http.NoStore;
import io.repsy.os.shared.http.ResponseEntities;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.os.shared.utils.MultiPortNames;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/profile")
class ProfileController {

  private final @NonNull PanelAuthHelper panelAuthHelper;
  private final @NonNull ProfileService profileService;
  private final @NonNull UserTxService userTxService;

  @GetMapping
  public @NonNull ProfileInfo get(@RequestHeader(AUTHORIZATION) final @NonNull String authHeader) {

    final var userId = this.panelAuthHelper.authenticate(authHeader).getId();

    final var profileInfo = this.profileService.getProfile(userId);

    return profileInfo;
  }

  @PatchMapping("/username")
  public @NonNull LoginInfo updateUsername(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @RequestBody @Valid final @NonNull UpdateUsernameForm form,
      final @NonNull HttpServletResponse response) {

    final var session = this.panelAuthHelper.authenticateSession(authHeader);

    final var loginInfo =
        this.profileService.updateUsername(
            session.user().getId(), form.getUsername(), session.sessionStart());

    NoStore.apply(response);

    return loginInfo;
  }

  @PatchMapping("/password")
  public @NonNull LoginInfo updatePassword(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @RequestBody @Valid final @NonNull PasswordForm form,
      final @NonNull HttpServletResponse response) {

    final var session = this.panelAuthHelper.authenticateSession(authHeader);

    final var loginInfo =
        this.profileService.updatePassword(session.user().getId(), form, session.sessionStart());

    NoStore.apply(response);

    return loginInfo;
  }

  @DeleteMapping
  public @NonNull ResponseEntity<Void> deleteProfile(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader) {

    final var userId = this.panelAuthHelper.authenticate(authHeader).getId();

    // A token whose user is already gone is an authentication failure, not a missing resource.
    this.userTxService.getAuthenticatedUserById(userId);
    this.userTxService.deleteUserById(userId);

    return ResponseEntities.noContent();
  }
}
