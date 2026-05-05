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
package io.repsy.protocols.helm.shared.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.experimental.UtilityClass;
import org.apache.commons.codec.digest.MessageDigestAlgorithms;
import org.jspecify.annotations.NullMarked;

/** Computes SHA-256 digests by streaming — no full-file buffering in memory. */
@UtilityClass
@NullMarked
public class HelmDigestCalculator {

  public static String calculate(final InputStream inputStream)
      throws IOException, NoSuchAlgorithmException {
    final var digest = MessageDigest.getInstance(MessageDigestAlgorithms.SHA_256);
    try (final var dis = new DigestInputStream(inputStream, digest)) {
      // drain the stream so the digest is fully computed
      dis.transferTo(OutputStream.nullOutputStream());
    }
    return HelmConstants.SHA256_PREFIX + HexFormat.of().formatHex(digest.digest());
  }
}
