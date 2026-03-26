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
package io.repsy.os.panel.auth.controllers;

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.panel.auth.dtos.LoginForm;
import io.repsy.os.panel.auth.dtos.RefreshTokenForm;
import io.repsy.os.panel.auth.services.AuthUserService;
import io.repsy.os.panel.shared.auth.dtos.LoginInfo;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.utils.MultiPortNames;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
class AuthController {

  private final @NonNull AuthUserService authUserService;
  private final @NonNull JwtUtils jwtUtils;
  private final @NonNull RestResponseFactory resp;

  @PostMapping("/login")
  public @NonNull RestResponse<LoginInfo> login(@RequestBody @Valid final @NonNull LoginForm form) {

    final var loginInfo = this.authUserService.login(form);

    return this.resp.success("loginSucceeded", loginInfo);
  }

  @PostMapping("/refresh-token")
  public @NonNull RestResponse<LoginInfo> refreshToken(
      @RequestBody @Valid final @NonNull RefreshTokenForm form) {

    this.jwtUtils.isRefreshTokenExpired(form.getRefreshToken());

    final var userId = this.jwtUtils.getUserId(form.getRefreshToken());

    final var loginInfo = this.authUserService.refreshToken(userId);

    return this.resp.success("tokenRefreshed", loginInfo);
  }
}
