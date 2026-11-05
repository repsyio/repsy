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

import lombok.experimental.UtilityClass;
import org.apache.commons.codec.digest.DigestUtils;

/**
 * A short fingerprint of a deploy token's stored hash (RPS-2431). The JWT minted from a deploy
 * token carries it as the {@code tf} claim and every request compares it with the current row, so a
 * rotation, which keeps the row and its id and replaces the hash, ends every JWT minted before it.
 * It is a hash of the stored hash, cut short, so the JWT (which anyone holding it can read) never
 * shows the stored value itself.
 */
@UtilityClass
public class DeployTokenFingerprintUtils {

  private static final int LENGTH = 16;

  public static String of(final String storedHash) {

    return DigestUtils.sha256Hex(storedHash).substring(0, LENGTH);
  }
}
