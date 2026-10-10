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

import io.repsy.core.web.http.NoStore;
import io.repsy.core.web.http.ResponseEntities;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.generated.model.LoginForm;
import io.repsy.os.generated.model.LoginInfo;
import io.repsy.os.generated.model.RefreshTokenForm;
import io.repsy.os.panel.auth.services.AuthUserService;
import io.repsy.os.shared.auth.services.RefreshTokenService;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.utils.MultiPortNames;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
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
  private final @NonNull RefreshTokenService refreshTokenService;

  @PostMapping("/login")
  public @NonNull LoginInfo login(
      @RequestBody @Valid final @NonNull LoginForm form,
      final @NonNull HttpServletResponse response) {

    // The form only limits the characters; BCrypt reads bytes. No account has a longer password.
    PasswordHasher.requireFitsBcrypt(form.getPassword());

    final var loginInfo = this.authUserService.login(form);

    NoStore.apply(response);

    return loginInfo;
  }

  @PostMapping("/tokens/refresh")
  public @NonNull LoginInfo refreshToken(
      @RequestBody @Valid final @NonNull RefreshTokenForm form,
      final @NonNull HttpServletResponse response) {

    final var claims = this.jwtUtils.verifyRefreshToken(form.getRefreshToken());

    final var loginInfo = this.authUserService.refreshToken(claims);

    NoStore.apply(response);

    return loginInfo;
  }

  /**
   * Ends the session the refresh token belongs to: every token of its family is revoked, so a copy
   * of it (leaked, or left in another tab's memory) can no longer be exchanged for a new pair. Like
   * {@code /tokens/refresh}, the refresh token itself is the credential, so this needs no {@code
   * Authorization} header. A token that fails to verify (expired, tampered, already rejected) has
   * nothing left to revoke that {@code consume} would not already have revoked on its own replay,
   * so it answers the same 401 the verify itself throws; a well-formed token of an already-revoked
   * or unknown family is accepted and revoked again for no further effect, so a client can always
   * call this and get a clean 204 on logout.
   */
  @PostMapping("/logout")
  public @NonNull ResponseEntity<Void> logout(
      @RequestBody @Valid final @NonNull RefreshTokenForm form) {

    final var claims = this.jwtUtils.verifyRefreshToken(form.getRefreshToken());

    this.refreshTokenService.revoke(claims.familyId());

    return ResponseEntities.noContent();
  }
}
