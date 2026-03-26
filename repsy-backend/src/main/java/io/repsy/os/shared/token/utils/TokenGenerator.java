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
package io.repsy.os.shared.token.utils;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.repsy.os.shared.token.dtos.TokenType;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.RandomStringUtils;
import org.jspecify.annotations.NonNull;

@UtilityClass
final class TokenGenerator {

  private static final @NonNull SecureRandom SECURE_RANDOM = new SecureRandom();
  private static final @NonNull String ALGORITHM = "SHA-256";
  private static final int TOKEN_LENGTH = 32;

  public static @NonNull String generate(final @NonNull TokenType tokenType) {

    try {
      return createToken(tokenType.getPrefix());
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(
          "Failed to generate token due to missing algorithm: " + ALGORITHM, e);
    }
  }

  private static @NonNull String createToken(final String prefix) throws NoSuchAlgorithmException {

    final var randomString =
        RandomStringUtils.random(TOKEN_LENGTH, 0, 0, true, true, null, SECURE_RANDOM);
    final var entropy = Instant.now().toEpochMilli() + "-" + System.nanoTime() + "-" + randomString;

    final var digest = MessageDigest.getInstance(ALGORITHM);

    final var hash = digest.digest(entropy.getBytes(UTF_8));

    return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
  }
}
