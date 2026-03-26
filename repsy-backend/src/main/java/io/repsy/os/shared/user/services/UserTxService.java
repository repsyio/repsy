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
package io.repsy.os.shared.user.services;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.shared.auth.utils.PasswordGeneratorUtil;
import io.repsy.os.shared.user.dtos.UserCreateForm;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.dtos.UserResponse;
import io.repsy.os.shared.user.dtos.UserUpdateForm;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.mappers.UserConverter;
import io.repsy.os.shared.user.repositories.UserRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class UserTxService {

  private static final @NonNull String ERR_USER_NOT_FOUND = "userNotFound";
  private static final @NonNull String ERR_USERNAME_IN_USE = "usernameInUse";
  private static final @NonNull String ERR_CANNOT_DELETE_LAST_ADMIN = "cannotDeleteLastAdminUser";

  private final @NonNull UserRepository userRepository;
  private final @NonNull UserConverter userConverter;

  @Transactional
  public @NonNull UserInfo create(
      final @NonNull String username,
      final @NonNull UserRole role,
      final @Nullable String hash,
      final @NonNull String salt) {

    if (this.userRepository.existsByUsername(username)) {
      throw new BadRequestException(ERR_USERNAME_IN_USE);
    }

    final var user = new User();
    user.setUsername(username);
    user.setRole(role);
    user.setHash(hash);
    user.setSalt(salt);

    this.userRepository.save(user);

    return this.userConverter.toUserInfo(user);
  }

  public @NonNull UserInfo getUserByUsername(final @NonNull String username) {
    return this.userConverter.toUserInfo(this.findUserByUsername(username));
  }

  public @NonNull UserInfo getUserById(final @NonNull UUID userId) {
    return this.userConverter.toUserInfo(this.findUserById(userId));
  }

  public @NonNull Optional<UserInfo> getUserByUsernameOptional(final @NonNull String username) {
    return this.userRepository.findByUsername(username).map(this.userConverter::toUserInfo);
  }

  public boolean existsByUsername(final @NonNull String username) {
    return this.userRepository.existsByUsername(username);
  }

  @Transactional
  public void updateUsername(final @NonNull UUID userId, final @NonNull String newUsername) {
    final var user = this.findUserById(userId);
    user.setUsername(newUsername);
    this.userRepository.save(user);
  }

  @Transactional
  public void updatePassword(
      final @NonNull UUID userId, final @NonNull String newHash, final @NonNull String newSalt) {

    final var user = this.findUserById(userId);
    user.setHash(newHash);
    user.setSalt(newSalt);
    this.userRepository.save(user);
  }

  public @NonNull Page<UserResponse> getAllUsers(
      final @NonNull String search, final @NonNull Pageable pageable) {

    return this.userRepository
        .findAllWithSearch(search, pageable)
        .map(this.userConverter::toUserResponseDto);
  }

  @Transactional
  public @NonNull UserResponse createUserWithRole(final @NonNull UserCreateForm dto) {
    if (this.userRepository.existsByUsername(dto.username())) {
      throw new BadRequestException(ERR_USERNAME_IN_USE);
    }

    final var salt = PasswordGeneratorUtil.generateSalt();
    final var hash = this.hashPassword(dto.password(), salt);

    final var user = new User();
    user.setUsername(dto.username());
    user.setHash(hash);
    user.setSalt(salt);
    user.setRole(dto.role());

    final var savedUser = this.userRepository.save(user);
    return this.userConverter.toUserResponseDto(savedUser);
  }

  @Transactional
  public @NonNull UserResponse updateUserDetails(
      final @NonNull UUID userId, final @NonNull UserUpdateForm dto) {

    final var user = this.findUserById(userId);

    if (!user.getUsername().equals(dto.username())) {
      if (this.userRepository.existsByUsername(dto.username())) {
        throw new BadRequestException(ERR_USERNAME_IN_USE);
      }
      user.setUsername(dto.username());
    }

    user.setRole(dto.role());

    return this.userConverter.toUserResponseDto(this.userRepository.save(user));
  }

  @Transactional
  public @NonNull String resetUserPassword(final @NonNull UUID userId) {
    final var user = this.findUserById(userId);
    final var newPassword = PasswordGeneratorUtil.generatePassword();
    final var salt = PasswordGeneratorUtil.generateSalt();

    user.setHash(this.hashPassword(newPassword, salt));
    user.setSalt(salt);

    this.userRepository.save(user);
    return newPassword;
  }

  @Transactional
  public void deleteUserById(final @NonNull UUID userId) {
    final var user = this.findUserById(userId);

    if (user.getRole() == UserRole.ADMIN && this.userRepository.countByRole(UserRole.ADMIN) <= 1) {
      throw new BadRequestException(ERR_CANNOT_DELETE_LAST_ADMIN);
    }

    this.userRepository.delete(user);
  }

  @Transactional
  public void updateLastLoginAt(final @NonNull String username) {
    final var user = this.findUserByUsername(username);
    user.setLastLoginAt(Instant.now());
    this.userRepository.save(user);
  }

  private @NonNull User findUserById(final @NonNull UUID id) {
    return this.userRepository
        .findById(id)
        .orElseThrow(() -> new ItemNotFoundException(ERR_USER_NOT_FOUND));
  }

  private @NonNull User findUserByUsername(final @NonNull String username) {
    return this.userRepository
        .findByUsername(username)
        .orElseThrow(() -> new ItemNotFoundException(ERR_USER_NOT_FOUND));
  }

  private @NonNull String hashPassword(final @NonNull String password, final @NonNull String salt) {
    return DigestUtils.sha256Hex(password + salt);
  }
}
