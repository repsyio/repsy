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

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.generated.model.LoginInfo;
import io.repsy.os.generated.model.PasswordForm;
import io.repsy.os.generated.model.ProfileInfo;
import io.repsy.os.generated.model.UpdateUsernameForm;
import io.repsy.os.panel.profile.services.ProfileService;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.os.shared.utils.MultiPortNames;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/profile")
class ProfileController {

  private final @NonNull JwtUtils jwtUtils;
  private final @NonNull ProfileService profileService;
  private final @NonNull UserTxService userTxService;
  private final @NonNull RestResponseFactory resp;

  @GetMapping
  public @NonNull RestResponse<ProfileInfo> get(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader) {

    this.jwtUtils.verify(authHeader);

    final var userId = this.jwtUtils.extractUserId(authHeader);

    final var profileInfo = this.profileService.getProfile(userId);

    return this.resp.success("profileFetched", profileInfo);
  }

  @PutMapping("/username")
  public @NonNull RestResponse<LoginInfo> updateUsername(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @RequestBody @Valid final @NonNull UpdateUsernameForm form) {

    final var userId = this.jwtUtils.extractUserId(authHeader);

    final var loginInfo = this.profileService.updateUsername(userId, form.getUsername());

    return this.resp.success("usernameUpdated", loginInfo);
  }

  @PutMapping("/password")
  public @NonNull RestResponse<Void> updatePassword(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @RequestBody @Valid final @NonNull PasswordForm form) {

    this.profileService.updatePassword(this.jwtUtils.extractUserId(authHeader), form);

    return this.resp.success("passwordChanged");
  }

  @DeleteMapping
  public @NonNull RestResponse<Void> deleteProfile(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader) {

    this.jwtUtils.verify(authHeader);

    final var userId = this.jwtUtils.extractUserId(authHeader);

    this.userTxService.deleteUserById(userId);

    return this.resp.success("profileDeleted");
  }
}
