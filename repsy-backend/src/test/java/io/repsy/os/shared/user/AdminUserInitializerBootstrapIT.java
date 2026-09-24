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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.user.entities.UserRole;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * RPS-1308: what {@code ADMIN_INITIAL_PASSWORD} lets the initializer create, and that the admin can
 * log in with it. The initializer only checks the complexity pattern (a lowercase letter, an
 * uppercase letter, a digit, no whitespace), with no length bound of its own. The login form
 * accepts 1 to 72 characters and no pattern, so a bootstrap password shorter than 6 or longer than
 * 50 characters no longer locks the admin out. The one limit left is BCrypt's 72 bytes: a longer
 * password stops the startup instead of creating an admin nobody can log in as.
 *
 * <p>The tests remove every admin inside the test transaction, so the initializer creates a new
 * one, and the rollback brings the seeded admin back.
 */
@DisplayName("AdminUserInitializer bootstrap password")
class AdminUserInitializerBootstrapIT extends AbstractIntegrationTest {

  @Autowired private AdminUserInitializer adminUserInitializer;

  private void bootstrapWith(final String password) {
    this.userRepository.deleteAll(this.userRepository.findAllByRole(UserRole.ADMIN));
    this.entityManager.flush();
    this.entityManager.clear();
    ReflectionTestUtils.setField(this.adminUserInitializer, "adminInitialPassword", password);

    try {
      this.adminUserInitializer.run(new DefaultApplicationArguments());
    } finally {
      ReflectionTestUtils.setField(
          this.adminUserInitializer, "adminInitialPassword", SEEDED_ADMIN_PASSWORD);
    }
    this.entityManager.flush();
  }

  private void login(final String password, final int expectedStatus) throws Exception {

    this.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"%s\"}".formatted(password)))
        .andExpect(status().is(expectedStatus));
  }

  static Stream<Arguments> acceptedPasswords() {
    return Stream.of(
        Arguments.of("6 characters, the old login minimum", "Abcde1"),
        Arguments.of("50 characters, the old login maximum", "Aa1" + "x".repeat(47)),
        Arguments.of("4 characters, below the old login minimum", "Ab1c"),
        Arguments.of("51 characters, above the old login maximum", "Aa1" + "x".repeat(48)),
        Arguments.of("72 characters, the BCrypt limit", "Aa1" + "x".repeat(69)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("acceptedPasswords")
  @DisplayName("an admin created with a password that meets the complexity rule can log in")
  void bootstrappedAdminLogsIn(final String name, final String password) throws Exception {
    this.bootstrapWith(password);

    assertThat(this.userRepository.findByUsername("admin")).isPresent();
    this.login(password, 200);
    this.login("!" + password.substring(1), 401);
  }

  @Test
  @DisplayName("a password that does not meet the complexity rule stops the startup")
  void weakPasswordStopsTheStartup() {
    assertThatThrownBy(() -> this.bootstrapWith("abc"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("complexity");
  }

  @Test
  @DisplayName("a password of more than 72 bytes stops the startup, since it could never log in")
  void overlongPasswordStopsTheStartup() {
    assertThatThrownBy(() -> this.bootstrapWith("Aa1" + "x".repeat(70)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("passwordTooLong");
  }
}
