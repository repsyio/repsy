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
package io.repsy.os.panel.profile.services;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.os.generated.model.LoginInfo;
import io.repsy.os.generated.model.PasswordForm;
import io.repsy.os.generated.model.ProfileInfo;
import io.repsy.os.generated.model.UserRole;
import io.repsy.os.panel.profile.repositories.ReservedUsernameRepository;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.PasswordGeneratorUtil;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.services.UserTxService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ProfileService {

  private final @NonNull JwtUtils jwtUtils;
  private final @NonNull ReservedUsernameRepository reservedUsernameRepository;
  private final @NonNull UserTxService userTxService;

  @Transactional
  public @NonNull LoginInfo updateUsername(
      final @NonNull UUID userId, final @NonNull String newUsername) {

    final var user = this.userTxService.getUserById(userId);

    this.validateUsernameAvailability(newUsername);

    user.setUsername(newUsername);
    this.userTxService.updateUsername(user.getId(), newUsername);

    return this.createLoginInfo(user, newUsername);
  }

  public ProfileInfo getProfile(final @NonNull UUID userId) {
    final var user = this.userTxService.getUserById(userId);

    return ProfileInfo.builder()
        .id(user.getId())
        .username(user.getUsername())
        .role(UserRole.valueOf(user.getRole().name()))
        .createdAt(user.getCreatedAt())
        .lastLoginAt(user.getLastLoginAt())
        .build();
  }

  @Transactional
  public void updatePassword(final @NonNull UUID userId, final @NonNull PasswordForm form) {

    final var user = this.userTxService.getUserById(userId);

    final var salt = PasswordGeneratorUtil.generateSalt();

    user.setSalt(salt);
    user.setHash(DigestUtils.sha256Hex(form.getPassword() + salt));
    this.userTxService.updatePassword(user.getId(), user.getHash(), salt);
  }

  private boolean isUsernameReserved(final @NonNull String username) {

    return this.reservedUsernameRepository.existsByUsername(username);
  }

  private void validateUsernameAvailability(final @NonNull String newUsername) {

    final var isUsernameReserved = this.isUsernameReserved(newUsername);
    final var isUsernameInUse = this.userTxService.existsByUsername(newUsername);

    if (isUsernameInUse || isUsernameReserved) {
      throw new BadRequestException("usernameInUse");
    }
  }

  private @NonNull LoginInfo createLoginInfo(
      final @NonNull UserInfo user, final @NonNull String newUsername) {

    final var accessToken =
        this.jwtUtils.createTokenWithDuration(
            user.getId(), newUsername, AuthUtils.TIMEOUT_ACCESS_TOKEN);
    final var refreshToken =
        this.jwtUtils.createTokenWithDuration(
            user.getId(), newUsername, AuthUtils.TIMEOUT_REFRESH_TOKEN);

    return LoginInfo.builder()
        .username(newUsername)
        .token(accessToken)
        .refreshToken(refreshToken)
        .build();
  }
}
