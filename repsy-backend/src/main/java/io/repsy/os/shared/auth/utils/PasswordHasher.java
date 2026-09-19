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
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;
import lombok.experimental.UtilityClass;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Hashes and verifies user passwords.
 *
 * <p>New hashes are BCrypt, stored as {@code "{bcrypt}$2a$..."}. The id in braces names the
 * algorithm, so a later change of algorithm or work factor stays verifiable, and {@link
 * #needsUpgrade} tells the caller to re-hash on the next successful login.
 *
 * <p>Hashes written before RPS-961 are a single salted SHA-256, {@code sha256Hex(password + salt)},
 * with no prefix. They still verify, and {@link #needsUpgrade} flags them, so they turn into BCrypt
 * the next time their owner logs in. A legacy hash is bare hex, so it never starts with an opening
 * brace.
 *
 * <p>BCrypt reads at most 72 bytes of the password. {@link #hash} rejects longer ones instead of
 * silently ignoring the tail.
 */
@UtilityClass
public class PasswordHasher {

  /** The largest password, in UTF-8 bytes, that BCrypt accepts. */
  public static final int MAX_PASSWORD_BYTES = 72;

  private static final String BCRYPT_ID = "bcrypt";
  private static final String ID_PREFIX = "{";
  private static final String PASSWORD_TOO_LONG = "passwordTooLong";
  private static final int DUMMY_PASSWORD_LENGTH = 32;

  /** Only algorithms listed here can verify, so a "noop" plain-text row cannot be planted. */
  private static final PasswordEncoder ENCODER =
      new DelegatingPasswordEncoder(BCRYPT_ID, Map.of(BCRYPT_ID, new BCryptPasswordEncoder()));

  /**
   * A real hash of a random password, made at the current work factor and checked when there is no
   * real hash to check. The password is generated per process, so no credential lives in the
   * source. It makes the failing paths (unknown user, wrong password on a legacy hash) cost about
   * one BCrypt, like a wrong password on a BCrypt hash.
   */
  private static final String DUMMY_HASH =
      encode(RandomStringUtils.secure().nextAlphanumeric(DUMMY_PASSWORD_LENGTH));

  /**
   * Hashes a password with the current algorithm.
   *
   * @throws BadRequestException if the password is longer than {@link #MAX_PASSWORD_BYTES}
   */
  public static @NonNull String hash(final @NonNull String password) {

    if (!fitsBcrypt(password)) {
      throw new BadRequestException(PASSWORD_TOO_LONG);
    }

    return encode(password);
  }

  /**
   * Checks a password against a stored hash without leaking, through timing, how much of it
   * matched.
   *
   * @param password the password to check
   * @param hash the stored hash, BCrypt or legacy SHA-256
   * @param salt the stored salt, used by legacy hashes only
   * @return whether the password is the one the hash was made from
   */
  public static boolean matches(
      final @NonNull String password, final @Nullable String hash, final @Nullable String salt) {

    if (hash == null) {
      verifyDummy(password);
      return false;
    }

    if (!isLegacy(hash)) {
      return matchesPrefixed(password, hash);
    }

    final var legacyHash = DigestUtils.sha256Hex(password + salt);
    final var matches = MessageDigest.isEqual(legacyHash.getBytes(UTF_8), hash.getBytes(UTF_8));

    if (!matches) {
      verifyDummy(password);
    }

    return matches;
  }

  /**
   * Whether the hash should be replaced, given the password that was just verified against it.
   * False for a legacy hash whose password BCrypt cannot take, since that one cannot be upgraded.
   */
  public static boolean needsUpgrade(final @NonNull String hash, final @NonNull String password) {

    if (isLegacy(hash)) {
      return fitsBcrypt(password);
    }

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

  private static boolean matchesPrefixed(
      final @NonNull String password, final @NonNull String hash) {

    try {
      return ENCODER.matches(password, hash);
    } catch (final IllegalArgumentException _) {
      // An algorithm id this build does not know.
      return false;
    }
  }

  private static boolean isLegacy(final @NonNull String hash) {

    return !hash.startsWith(ID_PREFIX);
  }

  private static boolean fitsBcrypt(final @NonNull String password) {

    return password.getBytes(UTF_8).length <= MAX_PASSWORD_BYTES;
  }
}
