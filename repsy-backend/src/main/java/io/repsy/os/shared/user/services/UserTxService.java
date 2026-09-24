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
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.generated.model.UserCreateForm;
import io.repsy.os.generated.model.UserResponse;
import io.repsy.os.generated.model.UserUpdateForm;
import io.repsy.os.shared.auth.utils.PasswordGeneratorUtil;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.mappers.UserConverter;
import io.repsy.os.shared.user.repositories.UserRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class UserTxService {

  private static final @NonNull String ERR_USER_NOT_FOUND = "userNotFound";
  private static final @NonNull String ERR_USERNAME_IN_USE = "usernameInUse";
  private static final @NonNull String ERR_CANNOT_DELETE_LAST_ADMIN = "cannotDeleteLastAdminUser";
  private static final @NonNull String ERR_CANNOT_DEMOTE_LAST_ADMIN = "cannotDemoteLastAdminUser";

  private final @NonNull UserRepository userRepository;
  private final @NonNull UserConverter userConverter;

  @Transactional
  public @NonNull UserInfo create(
      final @NonNull String username, final @NonNull UserRole role, final @Nullable String hash) {

    if (this.userRepository.existsByUsername(username)) {
      throw new BadRequestException(ERR_USERNAME_IN_USE);
    }

    final var user = new User();
    user.setUsername(username);
    user.setRole(role);
    user.setHash(hash);

    // The id is application-generated, so save() defers the INSERT; flush it so the
    // @CreationTimestamp value is populated before the entity is mapped to the result.
    final var savedUser = this.userRepository.saveAndFlush(user);

    return this.userConverter.toUserInfo(savedUser);
  }

  public @NonNull UserInfo getUserByUsername(final @NonNull String username) {
    return this.userConverter.toUserInfo(this.findUserByUsername(username));
  }

  public @NonNull UserInfo getUserById(final @NonNull UUID userId) {
    return this.userConverter.toUserInfo(this.findUserById(userId));
  }

  /**
   * Resolves the principal of a token that has already been verified. A principal that no longer
   * exists (deleted, or never existed) is an authentication failure, not a missing resource, so it
   * fails with {@code unAuthorized} and the client re-authenticates.
   */
  public @NonNull UserInfo getAuthenticatedUserByUsername(final @NonNull String username) {
    return this.getUserByUsernameOptional(username)
        .orElseThrow(() -> new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED));
  }

  /** Same as {@link #getAuthenticatedUserByUsername(String)}, for tokens that carry a user id. */
  public @NonNull UserInfo getAuthenticatedUserById(final @NonNull UUID userId) {
    return this.userRepository
        .findById(userId)
        .map(this.userConverter::toUserInfo)
        .orElseThrow(() -> new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED));
  }

  public @NonNull Optional<UserInfo> getUserByUsernameOptional(final @NonNull String username) {
    return this.userRepository.findByUsername(username).map(this.userConverter::toUserInfo);
  }

  public boolean existsByUsername(final @NonNull String username) {
    return this.userRepository.existsByUsername(username);
  }

  /**
   * Locks the user row for {@code userId} until the caller's transaction ends and tells whether it
   * is still there (RPS-1152). Call it inside the caller's own transaction, right before an
   * operation that would otherwise write a row referencing the user after it has already been
   * deleted: a concurrent deletion then either waits for this transaction to finish, or has already
   * committed and leaves nothing here to find.
   */
  @Transactional
  public boolean lockUserExists(final @NonNull UUID userId) {
    return this.userRepository.lockUserId(userId).isPresent();
  }

  @Transactional
  public void updateUsername(final @NonNull UUID userId, final @NonNull String newUsername) {
    final var user = this.findUserById(userId);
    user.setUsername(newUsername);
    user.revokeRefreshTokens();
    this.userRepository.save(user);
  }

  @Transactional
  public void updatePassword(final @NonNull UUID userId, final @NonNull String newHash) {

    final var user = this.findUserById(userId);
    user.setHash(newHash);
    user.revokeRefreshTokens();
    this.userRepository.save(user);
  }

  public @NonNull Page<UserResponse> getAllUsers(
      final @NonNull String search, final @NonNull Pageable pageable) {

    return this.userRepository
        .findAllWithSearch(search, pageable)
        .map(this.userConverter::toUserResponseDto);
  }

  public long countAdmins() {
    return this.userRepository.countByRole(UserRole.ADMIN);
  }

  @Transactional
  public @NonNull UserResponse createUserWithRole(final @NonNull UserCreateForm dto) {
    if (this.userRepository.existsByUsername(dto.getUsername())) {
      throw new BadRequestException(ERR_USERNAME_IN_USE);
    }

    final var hash = PasswordHasher.hash(dto.getPassword());

    final var user = new User();
    user.setUsername(dto.getUsername());
    user.setHash(hash);
    user.setRole(UserRole.valueOf(dto.getRole().name()));

    // The id is application-generated, so save() defers the INSERT; flush it so the
    // @CreationTimestamp value is populated before the entity is mapped to the response.
    final var savedUser = this.userRepository.saveAndFlush(user);
    return this.userConverter.toUserResponseDto(savedUser);
  }

  @Transactional
  public @NonNull UserResponse updateUserDetails(
      final @NonNull UUID userId, final @NonNull UserUpdateForm dto) {

    final var newRole = UserRole.valueOf(dto.getRole().name());

    final var user = this.findUserForRoleChange(userId, newRole);

    if (!user.getUsername().equals(dto.getUsername())) {
      if (this.userRepository.existsByUsername(dto.getUsername())) {
        throw new BadRequestException(ERR_USERNAME_IN_USE);
      }
      user.setUsername(dto.getUsername());
      user.revokeRefreshTokens();
    }

    user.setRole(newRole);

    return this.userConverter.toUserResponseDto(this.userRepository.save(user));
  }

  @Transactional
  public @NonNull String resetUserPassword(final @NonNull UUID userId) {
    final var user = this.findUserById(userId);
    final var newPassword = PasswordGeneratorUtil.generatePassword();

    user.setHash(PasswordHasher.hash(newPassword));
    user.revokeRefreshTokens();

    this.userRepository.save(user);
    return newPassword;
  }

  @Transactional
  public void deleteUserById(final @NonNull UUID userId) {
    // The admin rows are locked before the user is read and counted, so two requests that each
    // remove one of the last two admins run one after the other instead of both seeing two
    // (RPS-1101).
    final var adminCount = this.userRepository.lockIdsByRole(UserRole.ADMIN).size();
    final var user = this.findUserById(userId);

    if (user.getRole() == UserRole.ADMIN && adminCount <= 1) {
      throw new BadRequestException(ERR_CANNOT_DELETE_LAST_ADMIN);
    }

    this.userRepository.delete(user);
  }

  /**
   * Replaces the hash of a user who just logged in with a hash from the current algorithm
   * (RPS-961). Call it only when {@link PasswordHasher#needsUpgrade} is true, and only after the
   * password was verified against {@code user}'s hash. The password is not changing, so sessions
   * stay valid. If the password was changed in the meantime the stored hash no longer matches
   * {@code user} and nothing is written.
   *
   * <p>It runs in its own transaction: the caller may hold a read-only one (protocol facades do),
   * which could not write, and a failed upgrade must not roll the caller back.
   *
   * @param user the user as it was read for the login
   * @param password the password that was just verified against {@code user}'s hash
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void upgradePasswordHash(final @NonNull UserInfo user, final @NonNull String password) {

    this.userRepository.replaceHash(user.getId(), user.getHash(), PasswordHasher.hash(password));
  }

  /**
   * Records a login. It writes only the {@code last_login_at} column (RPS-1032): it runs on the
   * {@code @Async} login listener, and saving the whole entity could undo a password, role or
   * username change that committed after the row was read.
   *
   * @throws ItemNotFoundException if no user has {@code username}
   */
  @Transactional
  public void updateLastLoginAt(final @NonNull String username) {
    if (this.userRepository.updateLastLoginAt(username, Instant.now()) == 0) {
      throw new ItemNotFoundException(ERR_USER_NOT_FOUND);
    }
  }

  /**
   * Reads the user whose role is about to change to {@code newRole}, and refuses a change that
   * would leave the instance without an ADMIN. It checks before any field is touched, so a rejected
   * request leaves the user unmodified.
   *
   * <p>The admin rows are locked before the user is read, so a user that a concurrent request
   * demoted or deleted while this one waited is read as it is now. A request that keeps the role
   * ADMIN can only add admins, so it does not have to queue behind the others (RPS-1101).
   */
  private @NonNull User findUserForRoleChange(
      final @NonNull UUID userId, final @NonNull UserRole newRole) {

    final var mayDemote = newRole != UserRole.ADMIN;
    final var adminCount = mayDemote ? this.userRepository.lockIdsByRole(UserRole.ADMIN).size() : 0;
    final var user = this.findUserById(userId);

    if (mayDemote && user.getRole() == UserRole.ADMIN && adminCount <= 1) {
      throw new BadRequestException(ERR_CANNOT_DEMOTE_LAST_ADMIN);
    }

    return user;
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
}
