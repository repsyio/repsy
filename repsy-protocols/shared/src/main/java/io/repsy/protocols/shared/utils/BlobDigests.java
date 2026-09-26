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
package io.repsy.protocols.shared.utils;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;

/**
 * Checks a blob against the digest a registry client names for it. The digest is an OCI digest,
 * {@code algorithm:hex}, and the algorithms the OCI image specification registers ({@code sha256}
 * and {@code sha512}) are supported.
 */
@UtilityClass
@NullMarked
public class BlobDigests {

  private static final int BUFFER_SIZE = 64 * 1024;

  private static final Pattern DIGEST_PATTERN =
      Pattern.compile("^(?<algorithm>[a-z0-9]+):(?<hex>[0-9a-fA-F]+)$");

  /** The hex length of a digest, by the OCI name of its algorithm. */
  private static final Map<String, Integer> HEX_LENGTH_BY_ALGORITHM =
      Map.of("sha256", 64, "sha512", 128);

  private static final Map<String, String> JCA_NAME_BY_ALGORITHM =
      Map.of("sha256", "SHA-256", "sha512", "SHA-512");

  /**
   * A regular expression fragment (no capturing groups, no anchors) matching exactly the digests
   * {@link #isSupported} accepts: {@code sha256:} and 64 hex characters, or {@code sha512:} and 128
   * hex characters. Path parsers and route patterns embed this instead of a duplicated {@code
   * sha256:} pattern, so a digest the registry accepts is also one it routes.
   */
  public static final String DIGEST_REGEX =
      HEX_LENGTH_BY_ALGORITHM.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .map(entry -> entry.getKey() + ":[0-9a-fA-F]{" + entry.getValue() + "}")
          .collect(Collectors.joining("|", "(?:", ")"));

  /**
   * Tells whether {@code digest} is well formed and uses an algorithm this registry can check.
   *
   * @param digest The digest the client named
   * @return True for a {@code sha256:} or {@code sha512:} digest with the right hex length
   */
  public static boolean isSupported(final String digest) {

    final var matcher = DIGEST_PATTERN.matcher(digest);

    if (!matcher.matches()) {
      return false;
    }

    final var hexLength = HEX_LENGTH_BY_ALGORITHM.get(matcher.group("algorithm"));

    return hexLength != null && hexLength == matcher.group("hex").length();
  }

  /**
   * Tells whether {@code algorithm} is the OCI name of an algorithm this registry can check, for
   * example the value of the {@code digest-algorithm} parameter of a blob upload start.
   *
   * @param algorithm The algorithm name the client sent
   * @return True for {@code sha256} and {@code sha512}
   */
  public static boolean isSupportedAlgorithm(final String algorithm) {

    return HEX_LENGTH_BY_ALGORITHM.containsKey(algorithm);
  }

  /**
   * Tells whether {@code text} names a digest of a supported algorithm anywhere in it, that is,
   * contains {@code sha256:} or {@code sha512:}. It is a cheap dispatch test for a request path: it
   * does not validate the digest, which {@link #isSupported} or {@link #DIGEST_REGEX} do.
   *
   * @param text A request path or other text that may reference a digest
   * @return True when the text contains a supported algorithm's {@code algorithm:} prefix
   */
  public static boolean containsDigestPrefix(final String text) {

    return indexOfDigestPrefix(text) >= 0;
  }

  /**
   * Tells whether {@code text} starts with a supported algorithm's prefix, {@code sha256:} or
   * {@code sha512:}: a manifest reference is a digest, not a tag, exactly when it does.
   *
   * @param text A manifest reference or other text that may be a digest
   * @return True when the text starts with a supported algorithm's {@code algorithm:} prefix
   */
  public static boolean startsWithDigestPrefix(final String text) {

    return indexOfDigestPrefix(text) == 0;
  }

  /**
   * Finds where the first digest starts in {@code text}: the earliest occurrence of {@code sha256:}
   * or {@code sha512:}.
   *
   * @param text A storage path or other text that may end in a digest
   * @return The index of the digest's algorithm prefix, or {@code -1} when the text names no digest
   */
  public static int indexOfDigestPrefix(final String text) {

    return HEX_LENGTH_BY_ALGORITHM.keySet().stream()
        .mapToInt(algorithm -> text.indexOf(algorithm + ":"))
        .filter(index -> index >= 0)
        .min()
        .orElse(-1);
  }

  /**
   * Reads {@code content} to its end and tells whether it hashes to {@code digest}. The stream is
   * closed. A digest that is not {@linkplain #isSupported supported} matches nothing.
   *
   * @param digest The digest the client named
   * @param content The stored blob
   * @return True when the content hashes to the digest
   * @throws IOException When the content cannot be read
   */
  public static boolean matches(final String digest, final InputStream content) throws IOException {

    try (content) {
      if (!isSupported(digest)) {
        return false;
      }

      final var separator = digest.indexOf(':');
      final var messageDigest = newMessageDigest(digest.substring(0, separator));
      final var buffer = new byte[BUFFER_SIZE];

      int read;
      while ((read = content.read(buffer)) != -1) {
        messageDigest.update(buffer, 0, read);
      }

      final var actualHex = HexFormat.of().formatHex(messageDigest.digest());

      return actualHex.equals(digest.substring(separator + 1).toLowerCase(Locale.ROOT));
    }
  }

  private static MessageDigest newMessageDigest(final String algorithm) {

    try {
      return MessageDigest.getInstance(JCA_NAME_BY_ALGORITHM.get(algorithm));
    } catch (final NoSuchAlgorithmException e) {
      // SHA-256 and SHA-512 are required of every Java platform.
      throw new IllegalStateException(e);
    }
  }
}
