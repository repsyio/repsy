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
package io.repsy.protocols.shared.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import java.util.Locale;
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

  /** The salted SHA-256 of RPS-961 and before, which still verifies once (RPS-1615). */
  private static final String SHA256_HASH = DigestUtils.sha256Hex(PASSWORD + SALT);

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

      assertThat(PasswordHasher.matches(password, PasswordHasher.hash(password), null)).isTrue();
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
  @DisplayName("requireFitsBcrypt")
  class RequireFitsBcrypt {

    @Test
    @DisplayName("accepts 72 bytes, also as multi-byte characters")
    void acceptsTheLimit() {
      assertThatCode(
              () -> {
                PasswordHasher.requireFitsBcrypt("a".repeat(PasswordHasher.MAX_PASSWORD_BYTES));
                PasswordHasher.requireFitsBcrypt("é".repeat(36));
              })
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects 73 bytes, counting bytes and not characters")
    void rejectsOverTheLimit() {
      assertThatThrownBy(() -> PasswordHasher.requireFitsBcrypt("a".repeat(73)))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("passwordTooLong");
      assertThatThrownBy(() -> PasswordHasher.requireFitsBcrypt("é".repeat(37)))
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
      assertThat(PasswordHasher.matches(PASSWORD, PasswordHasher.hash(PASSWORD), null)).isTrue();
    }

    @Test
    @DisplayName("rejects another password on a BCrypt hash")
    void rejectsWrongPasswordOnBcrypt() {
      assertThat(PasswordHasher.matches("Wrong1!", PasswordHasher.hash(PASSWORD), null)).isFalse();
    }

    @Test
    @DisplayName("does not tell a password apart from the same one with a different tail")
    void isNotPrefixMatch() {
      assertThat(PasswordHasher.matches(PASSWORD + "x", PasswordHasher.hash(PASSWORD), null))
          .isFalse();
    }

    @Test
    @DisplayName("accepts the password of a salted SHA-256 hash with its salt (RPS-1615)")
    void acceptsLegacySha256() {
      assertThat(PasswordHasher.matches(PASSWORD, SHA256_HASH, SALT)).isTrue();
    }

    @Test
    @DisplayName("rejects another password, another salt and a lower-case hash on a SHA-256 hash")
    void rejectsWrongLegacyCredentials() {
      assertThat(PasswordHasher.matches("Wrong1!", SHA256_HASH, SALT)).isFalse();
      assertThat(PasswordHasher.matches(PASSWORD, SHA256_HASH, "fedcba9876543210")).isFalse();
      assertThat(PasswordHasher.matches(PASSWORD, SHA256_HASH.toUpperCase(Locale.ROOT), SALT))
          .isFalse();
    }

    @Test
    @DisplayName("never verifies a SHA-256 hash without its salt")
    void rejectsLegacyWithoutSalt() {
      assertThat(PasswordHasher.matches(PASSWORD, SHA256_HASH, null)).isFalse();
      assertThat(PasswordHasher.matches(PASSWORD, DigestUtils.sha256Hex(PASSWORD), null)).isFalse();
      assertThat(PasswordHasher.matches(PASSWORD, DigestUtils.sha256Hex(PASSWORD + "null"), null))
          .isFalse();
    }

    @Test
    @DisplayName("verifies a legacy password over 72 bytes, which BCrypt could not take")
    void acceptsOverlongLegacyPassword() {
      final var password = "a".repeat(100);

      assertThat(PasswordHasher.matches(password, DigestUtils.sha256Hex(password + SALT), SALT))
          .isTrue();
    }

    @Test
    @DisplayName("ignores the salt of a BCrypt hash")
    void bcryptIgnoresSalt() {
      assertThat(PasswordHasher.matches(PASSWORD, PasswordHasher.hash(PASSWORD), SALT)).isTrue();
    }

    @Test
    @DisplayName("a wrong password on a legacy hash costs a BCrypt check, like on a BCrypt hash")
    void wrongLegacyPasswordSpendsADummyCheck() {
      // Warm up, then compare orders of magnitude only: a SHA-256 alone takes microseconds, a
      // BCrypt check tens of milliseconds.
      PasswordHasher.matches("warm-up", SHA256_HASH, SALT);

      final var start = System.nanoTime();
      PasswordHasher.matches("Wrong1!", SHA256_HASH, SALT);
      final var elapsedMillis = (System.nanoTime() - start) / 1_000_000;

      assertThat(elapsedMillis).isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("rejects every password on the empty hash of a password reset")
    void rejectsEmptyHash() {
      assertThat(PasswordHasher.matches(PASSWORD, "", null)).isFalse();
      assertThat(PasswordHasher.matches(PASSWORD, "", SALT)).isFalse();
      assertThat(PasswordHasher.matches("", "", null)).isFalse();
      assertThat(PasswordHasher.matches(DigestUtils.sha256Hex(SALT), "", SALT)).isFalse();
    }

    @Test
    @DisplayName("rejects a missing hash")
    void rejectsNullHash() {
      assertThat(PasswordHasher.matches(PASSWORD, null, null)).isFalse();
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"{noop}Password1!", "{md5}anything", "{bcrypt", "{}x", "{bcrypt}"})
    @DisplayName("rejects a hash of an algorithm this build does not verify")
    void rejectsUnknownAlgorithm(final String hash) {
      assertThat(PasswordHasher.matches(PASSWORD, hash, null)).isFalse();
    }

    @Test
    @DisplayName("rejects a password over 72 bytes on a BCrypt hash without failing")
    void rejectsOverlongPasswordOnBcrypt() {
      assertThat(PasswordHasher.matches("a".repeat(200), PasswordHasher.hash(PASSWORD), null))
          .isFalse();
    }

    @Test
    @DisplayName("does not accept a password that only has the 72-byte prefix of the hashed one")
    void doesNotIgnoreBytesPastTheLimit() {
      final var hashed = "a".repeat(PasswordHasher.MAX_PASSWORD_BYTES);

      assertThat(PasswordHasher.matches(hashed + "tail", PasswordHasher.hash(hashed), null))
          .isFalse();
      assertThat(PasswordHasher.matches("é".repeat(37), PasswordHasher.hash("é".repeat(36)), null))
          .isFalse();
    }
  }

  @Nested
  @DisplayName("needsUpgrade")
  class NeedsUpgrade {

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
    @DisplayName("flags a salted SHA-256 hash, whose owner just proved the password (RPS-1615)")
    void flagsLegacySha256() {
      assertThat(PasswordHasher.needsUpgrade(SHA256_HASH, PASSWORD)).isTrue();
    }

    @Test
    @DisplayName("leaves a legacy hash alone when its password is too long for BCrypt")
    void leavesOverlongLegacyPassword() {
      final var password = "a".repeat(PasswordHasher.MAX_PASSWORD_BYTES + 1);

      assertThat(PasswordHasher.needsUpgrade(DigestUtils.sha256Hex(password + SALT), password))
          .isFalse();
    }

    @Test
    @DisplayName("has nothing to upgrade on the empty hash of a password reset")
    void leavesEmptyHash() {
      assertThat(PasswordHasher.needsUpgrade("", PASSWORD)).isFalse();
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
