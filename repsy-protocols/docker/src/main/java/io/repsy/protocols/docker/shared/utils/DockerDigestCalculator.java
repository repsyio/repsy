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
package io.repsy.protocols.docker.shared.utils;

import static java.security.MessageDigest.getInstance;

import io.repsy.protocols.shared.utils.BlobDigests;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import lombok.experimental.UtilityClass;
import org.apache.commons.codec.digest.MessageDigestAlgorithms;
import org.jspecify.annotations.NullMarked;

/**
 * Calculates the OCI digests of a manifest. A manifest is stored under its {@code sha256} digest
 * and also carries its {@code sha512} digest, so a client that names it by either algorithm finds
 * it.
 */
@UtilityClass
@NullMarked
public class DockerDigestCalculator {

  public static final String SHA256_PREFIX = "sha256:";
  public static final String SHA512_PREFIX = "sha512:";

  public static String calculateDigest(final byte[] bytes) throws NoSuchAlgorithmException {

    return SHA256_PREFIX
        + HexFormat.of().formatHex(getInstance(MessageDigestAlgorithms.SHA_256).digest(bytes));
  }

  public static String calculateSha512Digest(final byte[] bytes) throws NoSuchAlgorithmException {

    return SHA512_PREFIX
        + HexFormat.of().formatHex(getInstance(MessageDigestAlgorithms.SHA_512).digest(bytes));
  }

  /**
   * The form a digest is stored and looked up in: the hex digits are case-insensitive in a request
   * but stored lower-cased.
   */
  public static String normalize(final String digest) {

    return digest.toLowerCase(Locale.ROOT);
  }

  /**
   * The digest a response reports for a manifest: the reference itself when the client named the
   * manifest by a digest (in the algorithm it used, hex lower-cased), otherwise, for a tag, the
   * canonical {@code sha256} digest. A registry may report another algorithm than the requested
   * one, but a client that verifies the response against the digest it asked for (containerd,
   * skopeo) needs the same one back. The caller has already established that a digest reference
   * names the manifest, by the push check or the lookup.
   *
   * @param reference the tag or digest of the request
   * @param canonicalDigest the manifest's {@code sha256} digest
   * @return the digest to send in {@code Docker-Content-Digest} and {@code Location}
   */
  public static String reportedDigest(final String reference, final String canonicalDigest) {

    return BlobDigests.startsWithDigestPrefix(reference) ? normalize(reference) : canonicalDigest;
  }

  /**
   * Tells whether the (normalized) digest is a {@code sha512} one, so it is not a {@code sha256}.
   */
  public static boolean isSha512(final String digest) {

    return digest.startsWith(SHA512_PREFIX);
  }
}
