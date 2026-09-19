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
package io.repsy.os.shared.auth.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("PasswordHasher")
class PasswordHasherTest {

  private static final String PASSWORD = "Password1!";
  private static final String SALT = "0123456789abcdef";
  private static final String LEGACY_HASH = DigestUtils.sha256Hex(PASSWORD + SALT);

  @Nested
  @DisplayName("hash")
  class Hash {

    @Test
    @DisplayName("produces a BCrypt hash that names its algorithm")
    void producesPrefixedBcrypt() {
      assertThat(PasswordHasher.hash(PASSWORD)).startsWith("{bcrypt}$2");
    }

    @Test
    @DisplayName("salts every hash, so the same password hashes differently")
    void saltsEveryHash() {
      assertThat(PasswordHasher.hash(PASSWORD)).isNotEqualTo(PasswordHasher.hash(PASSWORD));
    }

    @Test
    @DisplayName("fits the varchar(128) hash column")
    void fitsTheColumn() {
      assertThat(PasswordHasher.hash("a".repeat(PasswordHasher.MAX_PASSWORD_BYTES)))
          .hasSizeLessThanOrEqualTo(128);
    }

    @Test
    @DisplayName("accepts a password of exactly 72 bytes")
    void acceptsTheLimit() {
      final var password = "a".repeat(PasswordHasher.MAX_PASSWORD_BYTES);

      assertThat(PasswordHasher.matches(password, PasswordHasher.hash(password), SALT)).isTrue();
    }

    @Test
    @DisplayName("rejects a password of 73 bytes instead of truncating it")
    void rejectsOneByteOverTheLimit() {
      final var password = "a".repeat(73);

      assertThatThrownBy(() -> PasswordHasher.hash(password))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("passwordTooLong");
    }

    @Test
    @DisplayName("counts bytes, not characters: 37 two-byte characters are too long")
    void countsBytes() {
      final var password = "é".repeat(37);

      assertThatThrownBy(() -> PasswordHasher.hash(password))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("passwordTooLong");
    }
  }

  @Nested
  @DisplayName("matches")
  class Matches {

    @Test
    @DisplayName("accepts the password of a BCrypt hash")
    void acceptsBcrypt() {
      assertThat(PasswordHasher.matches(PASSWORD, PasswordHasher.hash(PASSWORD), SALT)).isTrue();
    }

    @Test
    @DisplayName("rejects another password on a BCrypt hash")
    void rejectsWrongPasswordOnBcrypt() {
      assertThat(PasswordHasher.matches("Wrong1!", PasswordHasher.hash(PASSWORD), SALT)).isFalse();
    }

    @Test
    @DisplayName("does not tell a password apart from the same one with a different tail")
    void isNotPrefixMatch() {
      assertThat(PasswordHasher.matches(PASSWORD + "x", PasswordHasher.hash(PASSWORD), SALT))
          .isFalse();
    }

    @Test
    @DisplayName("still accepts the password of a legacy salted SHA-256 hash")
    void acceptsLegacy() {
      assertThat(PasswordHasher.matches(PASSWORD, LEGACY_HASH, SALT)).isTrue();
    }

    @Test
    @DisplayName("rejects another password on a legacy hash")
    void rejectsWrongPasswordOnLegacy() {
      assertThat(PasswordHasher.matches("Wrong1!", LEGACY_HASH, SALT)).isFalse();
    }

    @Test
    @DisplayName("rejects the right password with the wrong salt on a legacy hash")
    void rejectsWrongSaltOnLegacy() {
      assertThat(PasswordHasher.matches(PASSWORD, LEGACY_HASH, "fedcba9876543210")).isFalse();
    }

    @Test
    @DisplayName("rejects a missing hash")
    void rejectsNullHash() {
      assertThat(PasswordHasher.matches(PASSWORD, null, SALT)).isFalse();
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"{noop}Password1!", "{md5}anything", "{bcrypt", "{}x", "{bcrypt}"})
    @DisplayName("rejects a hash of an algorithm this build does not verify")
    void rejectsUnknownAlgorithm(final String hash) {
      assertThat(PasswordHasher.matches(PASSWORD, hash, SALT)).isFalse();
    }

    @Test
    @DisplayName("rejects a password over 72 bytes on a BCrypt hash without failing")
    void rejectsOverlongPasswordOnBcrypt() {
      assertThat(PasswordHasher.matches("a".repeat(200), PasswordHasher.hash(PASSWORD), SALT))
          .isFalse();
    }

    @Test
    @DisplayName("accepts a password over 72 bytes on a legacy hash, which had no such limit")
    void acceptsOverlongPasswordOnLegacy() {
      final var password = "a".repeat(200);

      assertThat(PasswordHasher.matches(password, DigestUtils.sha256Hex(password + SALT), SALT))
          .isTrue();
    }
  }

  @Nested
  @DisplayName("needsUpgrade")
  class NeedsUpgrade {

    @Test
    @DisplayName("flags a legacy hash")
    void flagsLegacy() {
      assertThat(PasswordHasher.needsUpgrade(LEGACY_HASH, PASSWORD)).isTrue();
    }

    @Test
    @DisplayName("leaves a current BCrypt hash alone")
    void leavesCurrentBcrypt() {
      assertThat(PasswordHasher.needsUpgrade(PasswordHasher.hash(PASSWORD), PASSWORD)).isFalse();
    }

    @Test
    @DisplayName("flags a BCrypt hash made with a lower work factor")
    void flagsWeakerWorkFactor() {
      final var weak = "{bcrypt}$2a$04$" + PasswordHasher.hash(PASSWORD).substring(15);

      assertThat(PasswordHasher.needsUpgrade(weak, PASSWORD)).isTrue();
    }

    @Test
    @DisplayName("does not flag a legacy hash whose password BCrypt could not take")
    void skipsLegacyWithOverlongPassword() {
      final var password = "a".repeat(200);

      assertThat(PasswordHasher.needsUpgrade(DigestUtils.sha256Hex(password + SALT), password))
          .isFalse();
    }
  }

  @Nested
  @DisplayName("verifyDummy")
  class VerifyDummy {

    @Test
    @DisplayName("never throws, whatever the password")
    void neverThrows() {
      assertThatCode(
              () -> {
                PasswordHasher.verifyDummy("");
                PasswordHasher.verifyDummy(PASSWORD);
                PasswordHasher.verifyDummy("a".repeat(500));
              })
          .doesNotThrowAnyException();
    }
  }
}
