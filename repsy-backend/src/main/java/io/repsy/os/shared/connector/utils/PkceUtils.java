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
package io.repsy.os.shared.connector.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * Proof Key for Code Exchange (RFC 7636), method {@code S256} only: the application sends the
 * challenge, {@code BASE64URL(SHA-256(verifier))} without padding, when it asks for a consent, and
 * proves it holds the verifier when it exchanges the code.
 */
@UtilityClass
public class PkceUtils {

  /** The one method accepted; {@code plain} would prove nothing. */
  public static final String METHOD_S256 = "S256";

  /** A verifier: 43 to 128 unreserved characters (RFC 7636, section 4.1). */
  private static final Pattern VERIFIER = Pattern.compile("[A-Za-z0-9._~-]{43,128}");

  /** An S256 challenge: the 32 bytes of a SHA-256, base64url without padding. */
  private static final Pattern CHALLENGE = Pattern.compile("[A-Za-z0-9_-]{43}");

  public static boolean isChallenge(final String challenge) {
    return CHALLENGE.matcher(challenge).matches();
  }

  /** The S256 challenge of {@code verifier}. */
  public static String challengeOf(final String verifier) {
    final var digest = sha256().digest(verifier.getBytes(StandardCharsets.US_ASCII));

    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
  }

  /**
   * Whether {@code verifier} is well formed and its S256 challenge is {@code challenge}, compared
   * in constant time.
   */
  public static boolean verify(final String verifier, final String challenge) {
    if (!VERIFIER.matcher(verifier).matches()) {
      return false;
    }

    return MessageDigest.isEqual(
        challengeOf(verifier).getBytes(StandardCharsets.US_ASCII),
        challenge.getBytes(StandardCharsets.US_ASCII));
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
