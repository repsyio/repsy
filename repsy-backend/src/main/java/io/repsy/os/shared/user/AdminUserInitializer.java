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
package io.repsy.os.shared.user;

import io.repsy.core.events.UserCreatedEvent;
import io.repsy.os.shared.auth.utils.PasswordGeneratorUtil;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.repositories.UserRepository;
import io.repsy.os.shared.user.services.UserTxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminUserInitializer implements ApplicationRunner {

  private static final String ADMIN_USERNAME = "admin";
  private static final String COMPLEXITY_PATTERN = "^(?=.*\\d)(?=.*[a-z])(?=.*[A-Z])(?=\\S+$).+$";
  private static final String COMPLEXITY_REQUIREMENTS =
      "at least one uppercase letter, one lowercase letter, one digit, and no whitespace";

  @Value("${admin.initial-password:}")
  private String adminInitialPassword;

  private final @NonNull UserTxService userTxService;
  private final @NonNull UserRepository userRepository;
  private final @NonNull ApplicationEventPublisher eventPublisher;

  @Override
  @Transactional
  public void run(final @NonNull ApplicationArguments args) {
    final var adminUserOpt = this.userRepository.findFirstByRole(UserRole.ADMIN);

    if (adminUserOpt.isPresent()) {
      this.handleExistingAdmin(adminUserOpt.get());
    } else {
      this.createNewAdmin();
    }
  }

  private void handleExistingAdmin(final @NonNull User adminUser) {
    if (adminUser.getHash() == null || adminUser.getHash().isEmpty()) {
      this.resetAdminPassword(adminUser);
    } else {
      log.debug("Admin user already exists, skipping initialization");
    }
  }

  private void resetAdminPassword(final @NonNull User adminUser) {
    log.debug("Admin user password is null, resetting with random password...");

    final var newPassword = PasswordGeneratorUtil.generatePassword();
    final var salt = PasswordGeneratorUtil.generateSalt();
    final var hash = PasswordGeneratorUtil.hashPassword(newPassword, salt);

    adminUser.setHash(hash);
    adminUser.setSalt(salt);
    this.userRepository.save(adminUser);

    log.warn("Admin password has been reset. New password: {}", newPassword);
  }

  private void createNewAdmin() {
    log.debug("Creating admin user...");

    final String password;
    if (this.adminInitialPassword == null || this.adminInitialPassword.isBlank()) {
      password = PasswordGeneratorUtil.generatePassword();
    } else if (!this.adminInitialPassword.matches(COMPLEXITY_PATTERN)) {
      throw new IllegalStateException(
          "initial-password does not meet complexity requirements: " + COMPLEXITY_REQUIREMENTS);
    } else {
      password = this.adminInitialPassword;
    }

    final var salt = PasswordGeneratorUtil.generateSalt();
    final var hash = PasswordGeneratorUtil.hashPassword(password, salt);

    final var userInfo = this.userTxService.create(ADMIN_USERNAME, UserRole.ADMIN, hash, salt);

    this.eventPublisher.publishEvent(
        new UserCreatedEvent<>(userInfo.getId(), userInfo.getUsername()));

    if (this.adminInitialPassword == null || this.adminInitialPassword.isBlank()) {
      log.warn(
          "Admin user created successfully with username: {} and temporarily generated password: {}",
          ADMIN_USERNAME,
          password);
    } else {
      log.info(
          "Admin user created successfully with username: {} and provided initial password",
          ADMIN_USERNAME);
    }
  }
}
