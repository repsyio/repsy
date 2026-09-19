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
import io.repsy.os.shared.auth.services.LoginInfoFactory;
import io.repsy.os.shared.auth.utils.PasswordGeneratorUtil;
import io.repsy.os.shared.user.services.UserTxService;
import java.time.Instant;
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

  private final @NonNull LoginInfoFactory loginInfoFactory;
  private final @NonNull ReservedUsernameRepository reservedUsernameRepository;
  private final @NonNull UserTxService userTxService;

  @Transactional
  public @NonNull LoginInfo updateUsername(
      final @NonNull UUID userId,
      final @NonNull String newUsername,
      final @NonNull Instant sessionStart) {

    this.userTxService.getAuthenticatedUserById(userId);

    this.validateUsernameAvailability(newUsername);

    this.userTxService.updateUsername(userId, newUsername);

    // Re-read so the tokens carry the username and the token version after the change.
    return this.loginInfoFactory.create(
        this.userTxService.getAuthenticatedUserById(userId), sessionStart);
  }

  public ProfileInfo getProfile(final @NonNull UUID userId) {
    final var user = this.userTxService.getAuthenticatedUserById(userId);

    return ProfileInfo.builder()
        .id(user.getId())
        .username(user.getUsername())
        .role(UserRole.valueOf(user.getRole().name()))
        .createdAt(user.getCreatedAt())
        .lastLoginAt(user.getLastLoginAt())
        .build();
  }

  @Transactional
  public @NonNull LoginInfo updatePassword(
      final @NonNull UUID userId,
      final @NonNull PasswordForm form,
      final @NonNull Instant sessionStart) {

    final var user = this.userTxService.getAuthenticatedUserById(userId);

    final var salt = PasswordGeneratorUtil.generateSalt();

    user.setSalt(salt);
    user.setHash(DigestUtils.sha256Hex(form.getPassword() + salt));
    this.userTxService.updatePassword(user.getId(), user.getHash(), salt);

    // Re-read so the returned tokens carry the incremented token version.
    return this.loginInfoFactory.create(this.userTxService.getUserById(userId), sessionStart);
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
}
