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

import static java.nio.charset.StandardCharsets.UTF_8;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import java.util.Map;
import java.util.Objects;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.RandomStringUtils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Hashes and verifies user passwords.
 *
 * <p>Hashes are BCrypt, stored as {@code "{bcrypt}$2a$..."}. The id in braces names the algorithm,
 * so a later change of algorithm or work factor stays verifiable, and {@link #needsUpgrade} tells
 * the caller to re-hash on the next successful login.
 *
 * <p>A hash without an id never verifies. That covers the empty hash an operator sets to recover a
 * lost password, and the salted SHA-256 hashes that RPS-961 replaced, which migration V0017
 * (RPS-1033) blanked out.
 *
 * <p>BCrypt reads at most 72 bytes of the password. {@link #hash} rejects longer ones instead of
 * silently ignoring the tail.
 */
@UtilityClass
public class PasswordHasher {

  /** The largest password, in UTF-8 bytes, that BCrypt accepts. */
  public static final int MAX_PASSWORD_BYTES = 72;

  private static final String BCRYPT_ID = "bcrypt";
  private static final String PASSWORD_TOO_LONG = "passwordTooLong";
  private static final int DUMMY_PASSWORD_LENGTH = 32;

  /** Only algorithms listed here can verify, so a "noop" plain-text row cannot be planted. */
  private static final PasswordEncoder ENCODER =
      new DelegatingPasswordEncoder(BCRYPT_ID, Map.of(BCRYPT_ID, new BCryptPasswordEncoder()));

  /**
   * A real hash of a random password, made at the current work factor and checked when there is no
   * real hash to check. The password is generated per process, so no credential lives in the
   * source. It makes the failing paths (unknown user, a hash that cannot verify) cost about one
   * BCrypt, like a wrong password on a BCrypt hash.
   */
  private static final String DUMMY_HASH =
      encode(RandomStringUtils.secure().nextAlphanumeric(DUMMY_PASSWORD_LENGTH));

  /**
   * Hashes a password with the current algorithm.
   *
   * @throws BadRequestException if the password is longer than {@link #MAX_PASSWORD_BYTES}
   */
  public static @NonNull String hash(final @NonNull String password) {

    requireFitsBcrypt(password);

    return encode(password);
  }

  /**
   * Rejects a password BCrypt would only read the first {@link #MAX_PASSWORD_BYTES} bytes of. A
   * hash can never be made from such a password, so no account has one; a login that sends one is
   * malformed input, not a wrong password.
   *
   * @throws BadRequestException if the password is longer than {@link #MAX_PASSWORD_BYTES}
   */
  public static void requireFitsBcrypt(final @NonNull String password) {

    if (!fitsBcrypt(password)) {
      throw new BadRequestException(PASSWORD_TOO_LONG);
    }
  }

  /**
   * Checks a password against a stored hash without leaking, through timing, how much of it
   * matched.
   *
   * @param password the password to check; one over {@link #MAX_PASSWORD_BYTES} never matches
   * @param hash the stored hash
   * @return whether the password is the one the hash was made from
   */
  public static boolean matches(final @NonNull String password, final @Nullable String hash) {

    // BCrypt ignores everything past 72 bytes, so it would accept the right password with any tail
    // added to it. No password of that length was ever hashed (hash() refuses one), so none
    // matches.
    if (hash == null || !fitsBcrypt(password)) {
      verifyDummy(password);
      return false;
    }

    try {
      return ENCODER.matches(password, hash);
    } catch (final IllegalArgumentException _) {
      // No algorithm id, or one this build does not know. Spend the time of a real check anyway.
      verifyDummy(password);
      return false;
    }
  }

  /** Whether the hash was made with an older algorithm or work factor and should be replaced. */
  public static boolean needsUpgrade(final @NonNull String hash) {

    return ENCODER.upgradeEncoding(hash);
  }

  /**
   * Spends the time of one hash check on nothing. Call it where a password is checked against no
   * user, so an unknown username answers as slowly as a wrong password.
   */
  public static void verifyDummy(final @NonNull String password) {

    ENCODER.matches(password, DUMMY_HASH);
  }

  /** {@link PasswordEncoder#encode} is nullable in the signature, but BCrypt always returns. */
  private static @NonNull String encode(final @NonNull String password) {

    return Objects.requireNonNull(ENCODER.encode(password), "the encoder returned no hash");
  }

  private static boolean fitsBcrypt(final @NonNull String password) {

    return password.getBytes(UTF_8).length <= MAX_PASSWORD_BYTES;
  }
}
